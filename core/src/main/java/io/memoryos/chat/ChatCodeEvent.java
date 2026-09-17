package io.memoryos.chat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Progress of one {@code run_python} call: the code the model wrote, its stdout and stderr as they are produced, and
 * the files it generated. As in Onyx, a failed call reports its error text on stderr before the failed stage.
 */
public record ChatCodeEvent(String toolCallId, Stage stage, @Nullable String code, @Nullable String output,
        List<GeneratedFile> files, @Nullable String stream) {
    /** Largest code and accumulated output the timeline carries; the model still receives the full result. */
    public static final int MAX_CODE_CHARACTERS = 8_000;
    public static final int MAX_OUTPUT_CHARACTERS = 16_000;
    public static final String STDOUT = "stdout";
    public static final String STDERR = "stderr";
    private static final Set<String> STREAMS = Set.of(STDOUT, STDERR);

    public enum Stage { RUNNING, OUTPUT, COMPLETED, FAILED }

    /** A file {@code run_python} produced, served at {@code /api/chat/file-artifacts/{id}/content}. */
    public record GeneratedFile(UUID id, String filename, String mediaType, long sizeBytes, boolean chart) {
        public GeneratedFile(UUID id, String filename, String mediaType, long sizeBytes) {
            this(id, filename, mediaType, sizeBytes, false);
        }

        public GeneratedFile {
            if (id == null || filename == null || filename.isBlank() || filename.length() > 200
                    || mediaType == null || mediaType.isBlank() || mediaType.length() > 100 || sizeBytes < 0)
                throw new IllegalArgumentException("Invalid generated file");
        }
    }

    public ChatCodeEvent {
        files = List.copyOf(files);
        // Output buffered before streams were labelled replays as stdout.
        if (stage == Stage.OUTPUT && stream == null) stream = STDOUT;
        if (toolCallId == null || toolCallId.isBlank() || toolCallId.length() > 256 || stage == null
                || (stage == Stage.RUNNING) != (code != null)
                || (stage == Stage.OUTPUT) != (output != null)
                || (stage == Stage.OUTPUT) != (stream != null)
                || stream != null && !STREAMS.contains(stream)
                || code != null && code.length() > MAX_CODE_CHARACTERS
                || output != null && output.length() > MAX_OUTPUT_CHARACTERS
                || files.size() > 25
                || !files.isEmpty() && stage != Stage.COMPLETED && stage != Stage.FAILED)
            throw new IllegalArgumentException("Invalid code event");
    }

    public static ChatCodeEvent running(String toolCallId, String code) {
        return new ChatCodeEvent(toolCallId, Stage.RUNNING, truncate(code, MAX_CODE_CHARACTERS), null, List.of(), null);
    }

    public static ChatCodeEvent output(String toolCallId, String stream, String output) {
        return new ChatCodeEvent(toolCallId, Stage.OUTPUT, null, output, List.of(), stream);
    }

    public static ChatCodeEvent completed(String toolCallId, List<GeneratedFile> files) {
        return new ChatCodeEvent(toolCallId, Stage.COMPLETED, null, null, files, null);
    }

    /** A run that timed out or exited non-zero still keeps the files it managed to produce. */
    public static ChatCodeEvent failed(String toolCallId, List<GeneratedFile> files) {
        return new ChatCodeEvent(toolCallId, Stage.FAILED, null, null, files, null);
    }

    public static ChatCodeEvent failed(String toolCallId) {
        return failed(toolCallId, List.of());
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
