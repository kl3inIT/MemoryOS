package io.memoryos.chat.tools;

import com.embabel.agent.api.annotation.LlmTool;
import io.memoryos.chat.ChatFileContentService;
import io.memoryos.chat.ChatToolActivity;
import io.memoryos.chat.UserFile;
import io.memoryos.chat.interpreter.InterpreterClient;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-turn Code Interpreter tool, ported from Onyx 40eb240df {@code python_tool.py}: file staging order, caps, notice,
 * name sanitizing, upload cache, result JSON, file reminder and the exception text returned on failure. Generated files
 * are linked through the owner-authorized MemoryOS artifact route instead of interpreter file ids.
 */
public final class RunPythonTool {
    static final int MAX_STAGED_FILES = 25;
    static final long MAX_STAGED_BYTES = 100L * 1024 * 1024;
    static final int MAX_OUTPUT_CHARACTERS = 50_000;
    /** Onyx runs each call with a fixed timeout; a MemoryOS turn has no total deadline of its own. */
    static final int DEFAULT_TIMEOUT_MS = 60_000;
    static final String MISSING_CODE = "The python tool requires a 'code' parameter containing the Python code to execute. "
            + "Please provide like: {\"code\": \"print('Hello, world!')\"}";
    static final String FILE_REMINDER = """
            Your code execution generated file(s) with download links.
            If you reference or share these files, use the exact markdown format [filename](file_link) with the file_link from the execution result.""";
    private static final Pattern UNSAFE_NAME = Pattern.compile("[\\x00-\\x1f/\\\\:*?\"<>|]+");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(RunPythonTool.class);
    private static final Map<String, String> MEDIA_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"), Map.entry("gif", "image/gif"), Map.entry("svg", "image/svg+xml"),
            Map.entry("csv", "text/csv"), Map.entry("txt", "text/plain"), Map.entry("md", "text/markdown"),
            Map.entry("html", "text/html"), Map.entry("json", "application/json"), Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"), Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"));

    private final InterpreterClient client;
    private final InterpreterService artifacts;
    private final ChatFileContentService files;
    private final ActorId actor;
    private final TenantId tenant;
    private final UUID messageId;
    private final Collection<UUID> fileIds;
    private final Runnable active;
    private final ChatToolActivity activity;
    private final java.util.function.Consumer<io.memoryos.chat.ChatCodeEvent> events;
    /** Onyx upload cache: (file name, content SHA-256) to service file id, for this turn only. */
    private final Map<String, String> uploads = new HashMap<>();

    public RunPythonTool(InterpreterClient client, InterpreterService artifacts, ChatFileContentService files, ActorId actor,
                         TenantId tenant, UUID messageId, Collection<UUID> fileIds, Runnable active,
                         ChatToolActivity activity, java.util.function.Consumer<io.memoryos.chat.ChatCodeEvent> events) {
        this.client = client; this.artifacts = artifacts; this.files = files; this.actor = actor; this.tenant = tenant;
        this.messageId = messageId; this.fileIds = List.copyOf(fileIds); this.active = active;
        this.activity = activity; this.events = events;
    }

    /** Publishes timeline progress for the call in flight; a tool call outside an inspected run publishes nothing. */
    private void publish(java.util.function.Function<String, io.memoryos.chat.ChatCodeEvent> event) {
        var call = activity.current();
        if (call != null) events.accept(event.apply(call.id()));
    }

    private record Candidate(UserFile file, String name, int order) {}
    private record Selection(List<Candidate> files, int dropped, int total) {}

    @LlmTool(name = "run_python", description = "Execute Python code in an isolated sandbox environment.")
    public synchronized String runPython(@LlmTool.Param(description = "Python source code to execute") @Nullable String code) {
        active.run();
        if (code == null || code.isBlank()) return MISSING_CODE;
        int timeoutMs = DEFAULT_TIMEOUT_MS;
        String notice = null;
        try {
            var selection = select(code);
            var failed = new ArrayList<String>();
            var staged = new ArrayList<InterpreterClient.StagedFile>();
            for (var candidate : selection.files()) {
                active.run();
                try {
                    staged.add(new InterpreterClient.StagedFile(candidate.name(), upload(candidate)));
                } catch (IOException | RuntimeException failure) {
                    LOG.warn("Code Interpreter could not stage an attachment ({})", failure.getClass().getSimpleName());
                    failed.add(candidate.name());
                }
            }
            notice = notice(selection, failed);
            publish(id -> io.memoryos.chat.ChatCodeEvent.running(id, code));
            // Streaming shows output while the code runs, and abandoning the read on Stop frees the service's slot.
            var streamed = new int[1];
            var execution = client.executeStream(code, timeoutMs, staged, (stream, data) -> {
                active.run();
                int room = io.memoryos.chat.ChatCodeEvent.MAX_OUTPUT_CHARACTERS - streamed[0];
                if (room <= 0 || data.isEmpty()) return;
                String delta = data.length() <= room ? data : data.substring(0, room);
                streamed[0] += delta.length();
                publish(id -> io.memoryos.chat.ChatCodeEvent.output(id,
                        io.memoryos.chat.ChatCodeEvent.STDERR.equals(stream) ? io.memoryos.chat.ChatCodeEvent.STDERR
                                : io.memoryos.chat.ChatCodeEvent.STDOUT, delta));
            });
            active.run();
            var generated = new ArrayList<Map<String, String>>();
            var produced = new ArrayList<io.memoryos.chat.ChatCodeEvent.GeneratedFile>();
            var tooLarge = new ArrayList<String>();
            for (var file : execution.files()) {
                if (!"file".equals(file.kind()) || file.fileId() == null) continue;
                active.run();
                String name = safeName(file.path().substring(file.path().lastIndexOf('/') + 1));
                try {
                    byte[] bytes = client.download(file.fileId());
                    active.run();
                    String mediaType = mediaType(name);
                    UUID id = artifacts.store(tenant, messageId, name, mediaType, bytes);
                    generated.add(Map.of("filename", name, "file_link", "/api/chat/file-artifacts/" + id + "/content"));
                    if (produced.size() < MAX_STAGED_FILES)
                        produced.add(new io.memoryos.chat.ChatCodeEvent.GeneratedFile(id, name, mediaType, bytes.length));
                } catch (InterpreterClient.TooLargeException large) {
                    tooLarge.add(name);
                } catch (IOException | RuntimeException failure) {
                    active.run();
                    LOG.warn("Code Interpreter could not store a generated file ({})", failure.getClass().getSimpleName());
                } finally {
                    delete(file.fileId());
                }
            }
            if (!tooLarge.isEmpty())
                notice = join(notice, tooLarge.size() + " generated file(s) larger than " + InterpreterClient.MAX_DOWNLOAD_BYTES
                        + " bytes were not returned: " + String.join(", ", tooLarge) + ".");
            String stderr = truncate(execution.stderr());
            Integer exit = execution.exitCode();
            String result = json(truncate(execution.stdout()), stderr, exit, execution.timedOut(), generated,
                    exit != null && exit == 0 ? null : stderr, notice);
            // A timeout or a non-zero exit is a failed run in the timeline, even though the tool still
            // answers the model; the files it managed to produce stay on the event.
            boolean unsuccessful = execution.timedOut() || exit == null || exit != 0;
            publish(id -> unsuccessful ? io.memoryos.chat.ChatCodeEvent.failed(id, List.copyOf(produced))
                    : io.memoryos.chat.ChatCodeEvent.completed(id, List.copyOf(produced)));
            return generated.isEmpty() ? result : result + "\n\n" + FILE_REMINDER;
        } catch (IOException | RuntimeException failure) {
            active.run(); // Cancellation must propagate, not become an ordinary tool result.
            activity.fail();
            // Onyx python_tool.py: the exception text reaches both the model and the timeline's stderr, unchanged.
            String error = failure.getMessage() == null || failure.getMessage().isBlank()
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            String shown = error.length() <= io.memoryos.chat.ChatCodeEvent.MAX_OUTPUT_CHARACTERS
                    ? error : error.substring(0, io.memoryos.chat.ChatCodeEvent.MAX_OUTPUT_CHARACTERS);
            publish(id -> io.memoryos.chat.ChatCodeEvent.output(id, io.memoryos.chat.ChatCodeEvent.STDERR, shown));
            publish(io.memoryos.chat.ChatCodeEvent::failed);
            LOG.warn("Code Interpreter execution failed ({})", failure.getClass().getSimpleName());
            return json("", error, -1, false, List.of(), error, notice);
        }
    }

    /** Onyx staging priority: files named in the code first, then the rest, newest first; staged in chronological order. */
    private Selection select(String code) {
        var chronological = new ArrayList<>(files.readable(actor, tenant, fileIds));
        chronological.sort(Comparator.comparing(UserFile::createdAt).thenComparing(UserFile::id));
        var used = new HashSet<String>();
        var candidates = new ArrayList<Candidate>();
        for (int i = 0; i < chronological.size(); i++) {
            var file = chronological.get(i);
            candidates.add(new Candidate(file, dedupe(safeName(file.filename()), used), i));
        }
        var referenced = new ArrayList<Candidate>();
        var others = new ArrayList<Candidate>();
        for (var candidate : candidates)
            (code.contains(candidate.file().filename()) || code.contains(candidate.name()) ? referenced : others).add(candidate);
        var priority = new ArrayList<Candidate>(referenced.reversed());
        priority.addAll(others.reversed());
        var selected = new ArrayList<Candidate>();
        long bytes = 0;
        for (var candidate : priority.subList(0, Math.min(MAX_STAGED_FILES, priority.size()))) {
            if (!selected.isEmpty() && bytes + candidate.file().sizeBytes() > MAX_STAGED_BYTES) break;
            selected.add(candidate);
            bytes += candidate.file().sizeBytes();
        }
        selected.sort(Comparator.comparingInt(Candidate::order));
        return new Selection(List.copyOf(selected), candidates.size() - selected.size(), candidates.size());
    }

    private String upload(Candidate candidate) throws IOException {
        try (var content = files.open(actor, tenant, candidate.file().id())) {
            String key = candidate.name() + ' ' + content.metadata().checksum().value();
            String cached = uploads.get(key);
            if (cached != null) return cached;
            String id = client.upload(candidate.name(), content.metadata().mediaType(), content.inputStream());
            uploads.put(key, id);
            return id;
        }
    }

    private void delete(String fileId) {
        try { client.delete(fileId); }
        catch (IOException | RuntimeException failure) {
            LOG.warn("Code Interpreter could not delete a generated file ({})", failure.getClass().getSimpleName());
        }
    }

    private static @Nullable String notice(Selection selection, List<String> failed) {
        String notice = null;
        if (selection.dropped() > 0)
            notice = selection.dropped() + " of " + selection.total() + " session files were not staged due to per-execution limits ("
                    + MAX_STAGED_FILES + " files / " + MAX_STAGED_BYTES + " bytes); files referenced in the code were prioritized.";
        if (!failed.isEmpty())
            notice = join(notice, "Failed to stage " + failed.size() + " file(s): " + String.join(", ", failed) + ".");
        return notice;
    }

    private static String join(@Nullable String first, String second) {
        return first == null ? second : first + " " + second;
    }

    static String safeName(String name) {
        String safe = UNSAFE_NAME.matcher(name).replaceAll("_").replaceAll("^[ .]+|[ .]+$", "");
        if (safe.isEmpty()) safe = "file";
        return safe.length() > 200 ? safe.substring(0, 200) : safe;
    }

    static String dedupe(String name, Set<String> used) {
        String candidate = name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 1; !used.add(candidate); index++) candidate = stem + "_" + index + extension;
        return candidate;
    }

    static String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARACTERS) return text;
        return text.substring(0, MAX_OUTPUT_CHARACTERS)
                + "\n... [output truncated, " + (text.length() - MAX_OUTPUT_CHARACTERS) + " characters omitted]";
    }

    static String mediaType(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MEDIA_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static String json(String stdout, String stderr, @Nullable Integer exitCode, boolean timedOut,
                               List<Map<String, String>> generated, @Nullable String error, @Nullable String notice) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "python_execution");
        result.put("stdout", stdout);
        result.put("stderr", stderr);
        result.put("exit_code", exitCode);
        result.put("timed_out", timedOut);
        result.put("generated_files", generated);
        result.put("error", error);
        result.put("staging_notice", notice);
        return JSON.writeValueAsString(result);
    }
}
