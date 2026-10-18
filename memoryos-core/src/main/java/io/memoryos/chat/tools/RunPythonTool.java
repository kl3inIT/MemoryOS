package io.memoryos.chat.tools;

import com.embabel.agent.api.annotation.LlmTool;
import io.memoryos.chat.ChatCodeEvent;
import io.memoryos.chat.ChatException;
import io.memoryos.library.UserFileContentService;
import io.memoryos.chat.ChatToolActivity;
import io.memoryos.library.UserFile;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.sandbox.SandboxException;
import io.memoryos.sandbox.SandboxService;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-turn Code Interpreter tool, ported from Onyx 40eb240df {@code python_tool.py}: file staging order, caps, notice,
 * name sanitizing, result JSON, file reminder and the exception text returned on failure. Since MEM-271 the code runs
 * as {@code python3} in the conversation's sandbox, beside the Sandbox tools, so the files it reads and writes stay in
 * {@code /workspace}; what the run writes or changes is handed to the user through the owner-authorized artifact route.
 */
public final class RunPythonTool {
    static final int MAX_STAGED_FILES = 25;
    static final long MAX_STAGED_BYTES = 100L * 1024 * 1024;
    static final int MAX_OUTPUT_CHARACTERS = 50_000;
    /** Onyx runs each call with a fixed timeout; a MemoryOS turn has no total deadline of its own. */
    static final int DEFAULT_TIMEOUT_MS = 60_000;
    static final int FILENAME_LIMIT = 200;
    /** The largest file a run hands to the user, as the interpreter returned at most 25 MiB. */
    static final long MAX_GENERATED_BYTES = 25L * 1024 * 1024;
    /** Where the source files behind this turn's search hits are staged (MEM-271 design). */
    static final String SEARCH = SandboxService.WORKSPACE + "/search";
    /** Written by the computer image's memoryos_charts capture: chart-{n}.png and, when recognised, chart-{n}.json. */
    static final String CHART_DIR = SandboxService.WORKSPACE + "/.memoryos-charts";
    static final int MAX_CHART_JSON_BYTES = 256 * 1024;
    /** What is read of each stream before {@link #truncate} cuts it to {@link #MAX_OUTPUT_CHARACTERS}. */
    private static final long OUTPUT_BYTES = 256 * 1024;
    private static final Pattern CHART_FILE = Pattern.compile("chart-(\\d{1,2})\\.(png|json)");
    static final String MISSING_CODE = "The python tool requires a 'code' parameter containing the Python code to execute. "
            + "Please provide like: {\"code\": \"print('Hello, world!')\"}";
    static final String FILE_REMINDER = """
            Your code execution generated file(s) with download links.
            If you reference or share these files, use the exact markdown format [filename](file_link) with the file_link from the execution result.""";
    /**
     * Onyx {@code wrap_last_line_interactive}: every statement runs in a namespace of its own, and a bare last
     * expression is compiled in 'single' mode so its value prints, as in Jupyter. The code is read from a file
     * rather than embedded in a string literal, so no quoting of it can break the wrapper.
     */
    static final String WRAPPER = """
            import ast
            import sys

            path = sys.argv[1]
            sys.argv = [path]
            sys.path[0] = "/workspace"
            with open(path, encoding="utf-8") as source:
                code = source.read()
            tree = ast.parse(code, "<stdin>")
            namespace = {"__name__": "__main__", "__file__": path, "__builtins__": __builtins__}
            if tree.body:
                for node in tree.body[:-1]:
                    exec(compile(ast.Module(body=[node], type_ignores=[]), "<stdin>", "exec"), namespace)
                last = tree.body[-1]
                if isinstance(last, ast.Expr):
                    interactive = ast.Interactive(body=[last])
                    ast.fix_missing_locations(interactive)
                    exec(compile(interactive, "<stdin>", "single"), namespace)
                else:
                    exec(compile(ast.Module(body=[last], type_ignores=[]), "<stdin>", "exec"), namespace)
            """;
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

    private final SandboxTools workspace;
    private final InterpreterService artifacts;
    private final UserFileContentService files;
    private final ActorId actor;
    private final TenantId tenant;
    private final UUID messageId;
    private final Collection<UUID> fileIds;
    private final Runnable active;
    private final ChatToolActivity activity;
    private final Consumer<ChatCodeEvent> events;

    public RunPythonTool(SandboxTools workspace, InterpreterService artifacts, UserFileContentService files, ActorId actor,
                         TenantId tenant, UUID messageId, Collection<UUID> fileIds, Runnable active,
                         ChatToolActivity activity, Consumer<ChatCodeEvent> events) {
        this.workspace = workspace; this.artifacts = artifacts; this.files = files; this.actor = actor; this.tenant = tenant;
        this.messageId = messageId; this.fileIds = List.copyOf(fileIds); this.active = active;
        this.activity = activity; this.events = events;
    }

    /** Publishes timeline progress for the call in flight; a tool call outside an inspected run publishes nothing. */
    private void publish(Function<String, ChatCodeEvent> event) {
        var call = activity.current();
        if (call != null) events.accept(event.apply(call.id()));
    }

    /** A file offered to the run: a chat attachment or the source file behind a search hit, and where it goes. */
    private record Candidate(String name, String original, long sizeBytes, int order, String directory, Opener opener) {
        String path() { return directory + "/" + name; }
    }
    private record Opened(InputStream input, Runnable closer) {}
    @FunctionalInterface private interface Opener { Opened open() throws IOException; }

    private @Nullable SandboxDocuments sandbox;

    /** Also stages the source files that search_knowledge found this turn, after the chat files, as Onyx llm_loop.py. */
    public RunPythonTool withSandbox(@Nullable SandboxDocuments sandbox) {
        this.sandbox = sandbox;
        return this;
    }
    private record Selection(List<Candidate> files, int dropped, int total) {}
    /** What the run left: its exit, its streams' sizes, and the files it wrote or changed and the charts it captured. */
    private record Ran(int exit, long stdoutBytes, long stderrBytes, List<Written> written, List<Written> charts) {}
    private record Written(String path, long sizeBytes) {}

    @LlmTool(name = "run_python", description = "Execute Python code in an isolated sandbox environment.")
    public synchronized String runPython(@LlmTool.Param(description = "Python source code to execute") @Nullable String code) {
        active.run();
        if (code == null || code.isBlank()) return MISSING_CODE;
        String notice = null;
        try {
            var selection = select(code);
            workspace.markUsed();
            var failed = stage(selection.files());
            notice = notice(selection, failed);
            publish(id -> ChatCodeEvent.running(id, code));
            String dir = SandboxTools.RUNS + UUID.randomUUID();
            workspace.write(dir + "/code.py", code.getBytes(StandardCharsets.UTF_8));
            workspace.write(dir + "/main.py", WRAPPER.getBytes(StandardCharsets.UTF_8));
            active.run();
            Duration timeout = Duration.ofMillis(DEFAULT_TIMEOUT_MS);
            var command = workspace.run(script(dir, timeout), timeout.plus(SandboxTools.WRAPPER_MARGIN));
            active.run();
            var ran = ran(command.stdout());
            if (ran == null) throw new IllegalStateException("The run's wrapper failed: " + command.stderr());
            // GNU timeout answers 124, or 137 when the code ignored the TERM and was killed after the grace.
            boolean timedOut = ran.exit() == 124 || ran.exit() == 137 && command.elapsed().compareTo(timeout) >= 0;
            String stdout = output(dir + "/out", ran.stdoutBytes());
            String stderr = output(dir + "/err", ran.stderrBytes());
            int room = ChatCodeEvent.MAX_OUTPUT_CHARACTERS;
            for (var stream : List.of(Map.entry(ChatCodeEvent.STDOUT, stdout), Map.entry(ChatCodeEvent.STDERR, stderr))) {
                if (room <= 0 || stream.getValue().isEmpty()) continue;
                String shown = stream.getValue().length() <= room ? stream.getValue() : stream.getValue().substring(0, room);
                room -= shown.length();
                publish(id -> ChatCodeEvent.output(id, stream.getKey(), shown));
            }
            var generated = new ArrayList<Map<String, String>>();
            var produced = new ArrayList<ChatCodeEvent.GeneratedFile>();
            var tooLarge = new ArrayList<String>();
            var noRoom = new ArrayList<String>();
            var charts = new ArrayList<Map<String, String>>();
            for (var file : ran.written()) {
                active.run();
                String name = safeName(file.path().substring(file.path().lastIndexOf('/') + 1));
                if (file.sizeBytes() > MAX_GENERATED_BYTES) {
                    tooLarge.add(name);
                    continue;
                }
                try {
                    byte[] bytes = workspace.read(SandboxService.WORKSPACE + "/" + file.path(), MAX_GENERATED_BYTES);
                    active.run();
                    String mediaType = mediaType(name);
                    UUID id = artifacts.store(tenant, messageId, name, mediaType, bytes);
                    generated.add(Map.of("filename", name, "file_link", "/api/chat/file-artifacts/" + id + "/content"));
                    if (produced.size() < MAX_STAGED_FILES)
                        produced.add(new ChatCodeEvent.GeneratedFile(id, name, mediaType, bytes.length));
                } catch (ChatException refused) {
                    // A full file library is the owner's business, not a tool failure: the answer says so.
                    if (!"CHAT_STORAGE_FULL".equals(refused.code())) throw refused;
                    noRoom.add(name);
                } catch (SandboxException unavailable) {
                    throw unavailable;
                } catch (RuntimeException failure) {
                    active.run();
                    LOG.atWarn().addKeyValue("event", "chat.interpreter.file_store_failed")
                            .addKeyValue("error_type", failure.getClass().getName()).log("Code Interpreter could not store a generated file");
                }
            }
            var chartFiles = new TreeMap<Integer, Map<String, Written>>();
            for (var file : ran.charts()) {
                var match = CHART_FILE.matcher(file.path());
                if (match.matches()) chartFiles.computeIfAbsent(Integer.valueOf(match.group(1)), _ -> new HashMap<>())
                        .put(match.group(2), file);
            }
            for (var chart : chartFiles.entrySet()) {
                active.run();
                Written png = chart.getValue().get("png");
                Written json = chart.getValue().get("json");
                if (png == null || png.sizeBytes() > MAX_GENERATED_BYTES) continue;
                try {
                    String data = json == null || json.sizeBytes() > MAX_CHART_JSON_BYTES ? null
                            : chartJson(workspace.read(CHART_DIR + "/" + json.path(), MAX_CHART_JSON_BYTES));
                    active.run();
                    byte[] bytes = workspace.read(CHART_DIR + "/" + png.path(), MAX_GENERATED_BYTES);
                    active.run();
                    var parsed = data == null ? null : JSON.readTree(data);
                    String title = parsed == null || !parsed.path("title").isString() ? "" : parsed.path("title").asString();
                    String name = safeName((title.isBlank() ? "chart-" + chart.getKey() : title) + ".png");
                    UUID id = artifacts.store(tenant, messageId, name, "image/png", bytes, data);
                    var entry = new LinkedHashMap<String, String>();
                    entry.put("title", title.isBlank() ? null : title);
                    entry.put("type", parsed == null ? "image" : parsed.path("type").asString("unknown"));
                    entry.put("file_link", "/api/chat/file-artifacts/" + id + "/content");
                    charts.add(entry);
                    if (produced.size() < MAX_STAGED_FILES)
                        produced.add(new ChatCodeEvent.GeneratedFile(id, name, "image/png", bytes.length, data != null));
                } catch (SandboxException unavailable) {
                    throw unavailable;
                } catch (RuntimeException failure) {
                    active.run();
                    LOG.atWarn().addKeyValue("event", "chat.interpreter.chart_store_failed")
                            .addKeyValue("error_type", failure.getClass().getName()).log("Code Interpreter could not store a captured chart");
                }
            }
            // The charts are this run's; the next run starts without them, and the workspace archive never holds them.
            if (!ran.charts().isEmpty()) workspace.run("rm -rf " + CHART_DIR, SandboxTools.WRAPPER_MARGIN);
            if (!noRoom.isEmpty())
                notice = join(notice, noRoom.size() + " generated file(s) were not kept because the user's file library is"
                        + " full: " + String.join(", ", noRoom) + ". Tell the user to free space in their library.");
            if (!tooLarge.isEmpty())
                notice = join(notice, tooLarge.size() + " generated file(s) larger than " + MAX_GENERATED_BYTES
                        + " bytes were not returned: " + String.join(", ", tooLarge) + ".");
            String shownStderr = truncate(stderr);
            Integer exit = timedOut ? null : ran.exit();
            String result = json(truncate(stdout), shownStderr, exit, timedOut, generated,
                    exit != null && exit == 0 ? null : shownStderr, notice, charts);
            // A timeout or a non-zero exit is a failed run in the timeline, even though the tool still
            // answers the model; the files it managed to produce stay on the event.
            boolean unsuccessful = timedOut || exit == null || exit != 0;
            publish(id -> unsuccessful ? ChatCodeEvent.failed(id, List.copyOf(produced))
                    : ChatCodeEvent.completed(id, List.copyOf(produced)));
            return generated.isEmpty() ? result : result + "\n\n" + FILE_REMINDER;
        } catch (RuntimeException failure) {
            active.run(); // Cancellation must propagate, not become an ordinary tool result.
            activity.fail();
            // Onyx python_tool.py: the exception text reaches both the model and the timeline's stderr, unchanged.
            String error = failure instanceof SandboxException unavailable ? unavailable.safeMessage()
                    : failure.getMessage() == null || failure.getMessage().isBlank()
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            String shown = error.length() <= ChatCodeEvent.MAX_OUTPUT_CHARACTERS
                    ? error : error.substring(0, ChatCodeEvent.MAX_OUTPUT_CHARACTERS);
            publish(id -> ChatCodeEvent.output(id, ChatCodeEvent.STDERR, shown));
            publish(ChatCodeEvent::failed);
            LOG.atWarn().addKeyValue("event", "chat.interpreter.execution_failed")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Code Interpreter execution failed");
            return json("", error, -1, false, List.of(), error, notice, List.of());
        }
    }

    /**
     * The run, as {@code bash} runs a command (MEM-270): from {@code /workspace} under {@code setsid timeout}, each
     * written file capped. A marker touched first dates the run, so the files newer than it are those it wrote or
     * changed; the run's own directory and what the workspace archive leaves out are not searched.
     */
    private static String script(String dir, Duration timeout) {
        return """
                cd %1$s
                rm -rf %2$s
                touch %3$s/started
                ulimit -f %4$d
                setsid timeout -k 5 %5$d python3 %3$s/main.py %3$s/code.py > %3$s/out 2> %3$s/err < /dev/null
                code=$?
                printf '%%s %%s %%s\\n' "$code" "$(stat -c %%s %3$s/out)" "$(stat -c %%s %3$s/err)"
                find %1$s \\( -path %1$s/.memoryos -o -path %2$s -o -name node_modules -o -name .venv -o -name __pycache__ \\
                  -o -name .next \\) -prune -o -type f -newer %3$s/started -printf 'F %%s %%P\\n'
                [ -d %2$s ] && find %2$s -maxdepth 1 -type f -printf 'C %%s %%f\\n'
                true
                """.formatted(SandboxService.WORKSPACE, CHART_DIR, dir, SandboxTools.FILE_LIMIT_BLOCKS, timeout.toSeconds());
    }

    /** The script's report, or null when the wrapper itself failed. */
    private static @Nullable Ran ran(String stdout) {
        String[] lines = stdout.split("\n");
        String[] summary = lines[0].strip().split(" ");
        if (summary.length != 3) return null;
        try {
            var written = new ArrayList<Written>();
            var charts = new ArrayList<Written>();
            for (int i = 1; i < lines.length; i++) {
                String[] parts = lines[i].split(" ", 3);
                if (parts.length != 3 || parts[2].isEmpty()) continue;
                var file = new Written(parts[2], Long.parseLong(parts[1]));
                if (parts[0].equals("F")) written.add(file);
                else if (parts[0].equals("C")) charts.add(file);
            }
            return new Ran(Integer.parseInt(summary[0]), Long.parseLong(summary[1]), Long.parseLong(summary[2]),
                    List.copyOf(written), List.copyOf(charts));
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    private String output(String file, long bytes) {
        if (bytes == 0) return "";
        if (bytes <= OUTPUT_BYTES) return new String(workspace.read(file, OUTPUT_BYTES), StandardCharsets.UTF_8);
        return workspace.run("head -c %d %s".formatted(OUTPUT_BYTES, file), SandboxTools.WRAPPER_MARGIN).stdout();
    }

    /** Onyx staging priority: files named in the code first, then the rest, newest first; staged in chronological order. */
    private Selection select(String code) {
        var chronological = new ArrayList<>(files.readable(actor, tenant, fileIds));
        chronological.sort(Comparator.comparing(UserFile::createdAt).thenComparing(UserFile::id));
        var used = new HashSet<String>();
        var candidates = new ArrayList<Candidate>();
        for (int i = 0; i < chronological.size(); i++) {
            var file = chronological.get(i);
            candidates.add(new Candidate(dedupe(safeName(file.filename()), file.id().toString(), used), file.filename(),
                    file.sizeBytes(), i, SandboxTools.ATTACHMENTS, () -> {
                        var content = files.open(actor, tenant, file.id());
                        return new Opened(content.inputStream(), () -> closeQuietly(content));
                    }));
        }
        if (sandbox != null) {
            var documents = sandbox;
            for (var document : documents.documents()) {
                candidates.add(new Candidate(dedupe(safeName(document.name()), document.documentId().toString(), used),
                        document.name(), document.sizeBytes(), candidates.size(), SEARCH, () -> {
                            var original = documents.open(document);
                            return new Opened(original.inputStream(), original::close);
                        }));
            }
        }
        var referenced = new ArrayList<Candidate>();
        var others = new ArrayList<Candidate>();
        for (var candidate : candidates)
            (code.contains(candidate.original()) || code.contains(candidate.name()) ? referenced : others).add(candidate);
        var priority = new ArrayList<>(referenced.reversed());
        priority.addAll(others.reversed());
        var selected = new ArrayList<Candidate>();
        long bytes = 0;
        for (var candidate : priority.subList(0, Math.min(MAX_STAGED_FILES, priority.size()))) {
            if (!selected.isEmpty() && bytes + candidate.sizeBytes() > MAX_STAGED_BYTES) break;
            selected.add(candidate);
            bytes += candidate.sizeBytes();
        }
        selected.sort(Comparator.comparingInt(Candidate::order));
        return new Selection(List.copyOf(selected), candidates.size() - selected.size(), candidates.size());
    }

    /**
     * Copies the selected files into the workspace, where {@code bash} finds the attachments too; a file already there
     * with the same size is kept, so a later call or turn copies nothing again. Answers the names that failed.
     */
    private List<String> stage(List<Candidate> selected) {
        var failed = new ArrayList<String>();
        if (selected.isEmpty()) return failed;
        var present = new HashMap<String, Long>();
        String listed = workspace.run("find %s %s -maxdepth 1 -type f -printf '%%s %%p\\n' 2>/dev/null || true"
                .formatted(SandboxTools.ATTACHMENTS, SEARCH), SandboxTools.WRAPPER_MARGIN).stdout();
        for (String line : listed.split("\n")) {
            int space = line.indexOf(' ');
            if (space > 0) present.put(line.substring(space + 1), Long.parseLong(line.substring(0, space)));
        }
        for (var candidate : selected) {
            if (Long.valueOf(candidate.sizeBytes()).equals(present.get(candidate.path()))) continue;
            active.run();
            try {
                var content = candidate.opener().open();
                try {
                    workspace.writeStream(candidate.path(), content.input());
                } finally {
                    content.closer().run();
                }
            } catch (SandboxException unavailable) {
                throw unavailable;
            } catch (IOException | RuntimeException failure) {
                active.run();
                LOG.atWarn().addKeyValue("event", "chat.interpreter.attachment_stage_failed")
                        .addKeyValue("error_type", failure.getClass().getName()).log("Code Interpreter could not stage an attachment");
                failed.add(candidate.name());
            }
        }
        return failed;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try { closeable.close(); } catch (Exception ignored) { /* the read already finished or failed */ }
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

    /** Onyx {@code _safe_code_interpreter_filename}: unsafe runs become "_", whitespace then dots are stripped, and a
     *  name over 200 characters keeps its extension. */
    static String safeName(String name) {
        String safe = UNSAFE_NAME.matcher(name).replaceAll("_").strip().replaceAll("^\\.+|\\.+$", "");
        if (safe.isEmpty()) return "file";
        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        String extension = dot > 0 ? safe.substring(dot) : "";
        return truncateBase(base, Math.max(1, FILENAME_LIMIT - extension.length())) + extension;
    }

    /** Onyx {@code _dedupe_code_interpreter_filename}: a repeated name gets the file's id before its extension. */
    static String dedupe(String name, String fallbackId, Set<String> used) {
        if (used.add(name)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String suffix = "_" + fallbackId + (dot > 0 ? name.substring(dot) : "");
        String deduped = truncateBase(base, Math.max(1, FILENAME_LIMIT - suffix.length())) + suffix;
        used.add(deduped);
        return deduped;
    }

    private static String truncateBase(String base, int limit) {
        return base.length() <= limit ? base : base.substring(0, limit);
    }

    /** Chart data is kept only as a bounded JSON object; anything else leaves the PNG without chart data. */
    static @Nullable String chartJson(byte[] bytes) {
        if (bytes.length > MAX_CHART_JSON_BYTES) return null;
        try {
            var node = JSON.readTree(bytes);
            return node != null && node.isObject() && node.path("type").isString() ? JSON.writeValueAsString(node) : null;
        } catch (RuntimeException invalid) {
            return null;
        }
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
                               List<Map<String, String>> generated, @Nullable String error, @Nullable String notice,
                               List<Map<String, String>> charts) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "python_execution");
        result.put("stdout", stdout);
        result.put("stderr", stderr);
        result.put("exit_code", exitCode);
        result.put("timed_out", timedOut);
        result.put("generated_files", generated);
        result.put("error", error);
        result.put("staging_notice", notice);
        // Figures left open are captured as charts the user sees interactively; their data stays out of the context.
        if (!charts.isEmpty()) result.put("charts", charts);
        return JSON.writeValueAsString(result);
    }
}
