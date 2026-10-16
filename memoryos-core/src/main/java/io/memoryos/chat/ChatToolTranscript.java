package io.memoryos.chat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The tool calls of one answer, kept so later turns of its branch replay them to the model (MEM-287), as Dust and
 * Anthropic replay earlier tool work. Every call keeps its arguments; only the tools in {@link #KEPT_RESULTS}, whose
 * output is the turn's own work or public, keep their result. Replaying stored organization or personal content would
 * outlive a revoked permission, a deleted file or a forgotten memory.
 */
public final class ChatToolTranscript {
    /** Tools whose result is replayed: sandbox and Python work, the public web and generated images. */
    public static final Set<String> KEPT_RESULTS = Set.of("bash", "bash_output", "edit_file", "publish_file",
            "run_python", "web_search", "open_url", "generate_image", "edit_image");
    /** In place of a result that was not kept, as Onyx replays its earlier calls. */
    public static final String NOT_KEPT = "This tool call completed but its result is not kept.";
    /** The bound of a call's arguments and of its result, as the column checks enforce. */
    public static final int FIELD_BYTES = 65_536;
    /** All calls of one answer together; past it the remaining results are not kept. */
    public static final int MESSAGE_BYTES = 262_144;
    private static final String CUT = "\n[cut: the rest of this result is not kept]";
    private static final String ARGUMENTS_NOT_KEPT = "{\"note\":\"The arguments of this call were too large to keep.\"}";

    /** One call: the provider's call id, the tool, its JSON arguments and its result when kept. */
    public record Call(String callId, String name, String arguments, @Nullable String result) {
        /** What the model is shown for the result. */
        public String shownResult() {
            return result == null ? NOT_KEPT : result;
        }
    }

    private final List<Call> calls = new ArrayList<>();
    private long bytes;

    /** Keeps one finished call, applying the kept-result list and the byte bounds. */
    public synchronized void record(String callId, String name, @Nullable String arguments, @Nullable String result) {
        // Arguments are JSON the provider parses back, so they are kept whole or replaced, never cut.
        String args = arguments == null || arguments.isBlank() ? "{}"
                : utf8(arguments) <= FIELD_BYTES ? arguments : ARGUMENTS_NOT_KEPT;
        String kept = KEPT_RESULTS.contains(name) && result != null ? bounded(result, CUT) : null;
        long size = utf8(args) + (kept == null ? 0 : utf8(kept));
        if (kept != null && bytes + size > MESSAGE_BYTES) {
            kept = null;
            size = utf8(args);
        }
        bytes += size;
        calls.add(new Call(callId, name, args, kept));
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    /** {@code text} within {@link #FIELD_BYTES} UTF-8 bytes, ending with {@code marker} when cut. */
    static String bounded(String text, String marker) {
        if (utf8(text) <= FIELD_BYTES) return text;
        int limit = FIELD_BYTES - utf8(marker);
        int end = 0;
        int used = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (used + size > limit) break;
            used += size;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end) + marker;
    }

    private static int utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
