package io.memoryos.chat.tools;

import com.embabel.agent.api.annotation.LlmTool;
import io.memoryos.chat.ChatCodeEvent;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatToolActivity;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.sandbox.SandboxCommand;
import io.memoryos.sandbox.SandboxCommandResult;
import io.memoryos.sandbox.SandboxEntry;
import io.memoryos.sandbox.SandboxException;
import io.memoryos.sandbox.SandboxService;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Sandbox group (MEM-270): five tools after Claude's bash and text editor, here {@code bash}, {@code bash_output},
 * {@code edit_file} and {@code publish_file}, working in the conversation's sandbox. Every command runs from a file
 * under {@code setsid timeout} (spring-agent writes the command to a file; Dust kills at the timeout and keeps the
 * output so far), and long output keeps its head and tail with the path of the whole (spring-agent's large-result
 * pointer). Descriptions borrow from Claude Code, spring-ai-agent-utils (Apache 2.0) and agents-flex.
 */
public final class SandboxTools {
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(120);
    static final Duration BACKGROUND_LIMIT = Duration.ofMinutes(30);
    /** What one tool result carries of a command's output: the head and the tail, as Dust and Claude Code bound it. */
    static final int OUTPUT_CHARACTERS = 30_000;
    static final long VIEW_BYTES = 4L * 1024 * 1024;
    static final int VIEW_LINES = 2_000;
    static final long PUBLISH_BYTES = 100L * 1024 * 1024;
    static final String RUNS = SandboxService.WORKSPACE + "/.memoryos/run/";
    /** Margin for the wrapper's own work around the command's timeout. */
    private static final Duration WRAPPER_MARGIN = Duration.ofSeconds(20);
    /** A file the command writes stops at 100 MiB (ulimit -f counts 1 KiB blocks). */
    private static final int FILE_LIMIT_BLOCKS = 102_400;
    private static final Pattern BASH_ID = Pattern.compile("[0-9a-f-]{36}");
    private static final Logger LOG = LoggerFactory.getLogger(SandboxTools.class);

    private final SandboxService sandboxes;
    private final InterpreterService artifacts;
    private final TenantId tenant;
    private final ActorId actor;
    private final UUID conversation;
    private final UUID messageId;
    private final Runnable active;
    private final ChatToolActivity activity;
    private final Consumer<ChatCodeEvent> events;

    public SandboxTools(SandboxService sandboxes, InterpreterService artifacts, TenantId tenant, ActorId actor,
                        UUID conversation, UUID messageId, Runnable active, ChatToolActivity activity,
                        Consumer<ChatCodeEvent> events) {
        this.sandboxes = sandboxes;
        this.artifacts = artifacts;
        this.tenant = tenant;
        this.actor = actor;
        this.conversation = conversation;
        this.messageId = messageId;
        this.active = active;
        this.activity = activity;
        this.events = events;
    }

    @LlmTool(name = "bash", description = """
            Executes a bash command or script in this conversation's Linux sandbox, in /workspace, and returns its \
            exit code, stdout and stderr. Files in /workspace persist across calls and turns. Commands stop after \
            timeout_seconds (default 60, at most 120); set run_in_background for longer work and read it with \
            bash_output. Output over 30,000 characters keeps its beginning and end and names the file holding all \
            of it. Use rg and fd to search; use edit_file to view or edit files.""")
    public synchronized String bash(
            @LlmTool.Param(description = "The command or script to run") @Nullable String command,
            @LlmTool.Param(description = "What the command does, in 5 to 10 words, e.g. 'Sum sales by month'", required = false) @Nullable String description,
            @LlmTool.Param(description = "Seconds before the command is stopped: default 60, at most 120", required = false) @Nullable Integer timeoutSeconds,
            @LlmTool.Param(description = "Run in the background, up to 30 minutes, and return a bash_id at once", required = false) @Nullable Boolean runInBackground) {
        active.run();
        if (command == null || command.isBlank()) return "The bash tool requires a 'command'.";
        String id = UUID.randomUUID().toString();
        String dir = RUNS + id;
        publish(call -> ChatCodeEvent.running(call, command, description));
        try {
            sandboxes.write(tenant, actor, conversation, dir + "/cmd.sh", command.getBytes(StandardCharsets.UTF_8));
            if (Boolean.TRUE.equals(runInBackground)) return background(id, dir);
            Duration timeout = timeout(timeoutSeconds);
            active.run();
            var run = run("""
                    cd %s
                    ulimit -f %d
                    setsid timeout -k 5 %d bash %s/cmd.sh > %s/out 2> %s/err < /dev/null
                    code=$?
                    printf '%%s %%s %%s' "$code" "$(stat -c %%s %s/out)" "$(stat -c %%s %s/err)"
                    """.formatted(SandboxService.WORKSPACE, FILE_LIMIT_BLOCKS, timeout.toSeconds(), dir, dir, dir, dir, dir),
                    timeout.plus(WRAPPER_MARGIN));
            active.run();
            String[] summary = run.stdout().strip().split(" ");
            if (summary.length != 3) throw new IllegalStateException("The command wrapper failed: " + run.stderr());
            int exit = Integer.parseInt(summary[0]);
            boolean timedOut = exit == 124 || exit == 137;
            String stdout = output(dir + "/out", Long.parseLong(summary[1]));
            String stderr = output(dir + "/err", Long.parseLong(summary[2]));
            if (!stdout.isEmpty()) publish(call -> ChatCodeEvent.output(call, ChatCodeEvent.STDOUT, shown(stdout)));
            if (!stderr.isEmpty()) publish(call -> ChatCodeEvent.output(call, ChatCodeEvent.STDERR, shown(stderr)));
            publish(call -> exit == 0 ? ChatCodeEvent.completed(call, List.of()) : ChatCodeEvent.failed(call));
            var result = new StringBuilder("exit_code: ").append(exit).append('\n');
            if (timedOut) result.append("timed out: the command was stopped after ").append(timeout.toSeconds())
                    .append(" seconds; the output below is what it wrote before\n");
            if (!stdout.isEmpty()) result.append("stdout:\n").append(stdout).append('\n');
            if (!stderr.isEmpty()) result.append("stderr:\n").append(stderr).append('\n');
            return result.toString();
        } catch (SandboxException unavailable) {
            return failed("bash", unavailable.safeMessage());
        } catch (RuntimeException failure) {
            active.run();
            return failed("bash", "The command could not run: " + message(failure));
        }
    }

    private String background(String id, String dir) {
        sandboxes.keepAlive(tenant, actor, conversation, BACKGROUND_LIMIT.plusMinutes(5));
        var run = run("""
                cd %s
                ulimit -f %d
                echo 0 > %s/offset
                setsid bash -c 'timeout -k 5 %d bash "$0/cmd.sh" > "$0/out" 2>&1 < /dev/null; echo $? > "$0/exit"' %s \
                  > /dev/null 2>&1 < /dev/null &
                echo $! > %s/pid
                """.formatted(SandboxService.WORKSPACE, FILE_LIMIT_BLOCKS, dir, BACKGROUND_LIMIT.toSeconds(), dir, dir),
                WRAPPER_MARGIN);
        if (run.exitCode() != 0) throw new IllegalStateException("The background command did not start: " + run.stderr());
        publish(call -> ChatCodeEvent.completed(call, List.of()));
        return "Started in the background with bash_id " + id + ". Read its output with bash_output; it stops after "
                + BACKGROUND_LIMIT.toMinutes() + " minutes.";
    }

    @LlmTool(name = "bash_output", description = """
            Returns the output a background bash command wrote since the last read, and whether it is still running \
            or how it ended. Set kill to stop the command first (its whole process group).""")
    public synchronized String bashOutput(
            @LlmTool.Param(description = "The bash_id bash returned") @Nullable String bashId,
            @LlmTool.Param(description = "A regular expression; only matching lines are returned", required = false) @Nullable String filter,
            @LlmTool.Param(description = "Stop the command before reading", required = false) @Nullable Boolean kill) {
        active.run();
        if (bashId == null || !BASH_ID.matcher(bashId).matches()) return "Unknown bash_id.";
        Pattern lines;
        try {
            lines = filter == null || filter.isBlank() ? null : Pattern.compile(filter);
        } catch (PatternSyntaxException invalid) {
            return "The filter is not a valid regular expression: " + invalid.getDescription();
        }
        String dir = RUNS + bashId;
        try {
            var run = run("""
                    d=%s
                    [ -f "$d/pid" ] || { echo missing; exit 0; }
                    # The command's session: setsid made it, and it holds every process the command started, including
                    # the process group GNU timeout makes for itself, which a group kill would miss.
                    sid=$(cat "$d/pid")
                    if [ "%s" = yes ] && [ ! -f "$d/exit" ]; then
                      pkill -TERM -s "$sid" 2>/dev/null; sleep 1; pkill -KILL -s "$sid" 2>/dev/null
                      [ -f "$d/exit" ] || echo 143 > "$d/exit"
                    fi
                    off=$(cat "$d/offset"); size=$(stat -c %%s "$d/out")
                    n=$((size - off)); [ "$n" -gt %d ] && n=%d
                    if [ -f "$d/exit" ]; then state="ended $(cat "$d/exit")"
                    elif pgrep -s "$sid" > /dev/null 2>&1; then state=running
                    else state="ended unknown"; fi
                    echo $((off + n)) > "$d/offset"
                    printf '%%s %%s %%s\\n' "$state" "$n" "$((size - off - n))"
                    tail -c +$((off + 1)) "$d/out" | head -c "$n"
                    """.formatted(dir, Boolean.TRUE.equals(kill) ? "yes" : "no", OUTPUT_CHARACTERS, OUTPUT_CHARACTERS),
                    WRAPPER_MARGIN);
            active.run();
            String text = run.stdout();
            if (text.strip().equals("missing")) return "Unknown bash_id.";
            int newline = text.indexOf('\n');
            String header = newline < 0 ? text : text.substring(0, newline);
            String output = newline < 0 ? "" : text.substring(newline + 1);
            if (lines != null) {
                var kept = new ArrayList<String>();
                for (String line : output.split("\n", -1)) if (lines.matcher(line).find()) kept.add(line);
                output = String.join("\n", kept);
            }
            String[] parts = header.split(" ");
            var result = new StringBuilder("status: ");
            result.append(parts[0].equals("running") ? "running" : "ended, exit_code " + parts[1]).append('\n');
            String left = parts[parts.length - 1];
            if (!"0".equals(left)) result.append(left).append(" more bytes are waiting; call bash_output again\n");
            result.append("output:\n").append(output);
            return result.toString();
        } catch (SandboxException unavailable) {
            return failed("bash_output", unavailable.safeMessage());
        } catch (RuntimeException failure) {
            active.run();
            return failed("bash_output", "The output could not be read: " + message(failure));
        }
    }

    @LlmTool(name = "edit_file", description = """
            Views, creates and edits files in the sandbox, as Claude's text editor. Commands: view (a file with \
            line numbers, or a directory two levels deep), create (writes file_text, creating parent directories), \
            str_replace (replaces old_str, which must occur exactly once, with new_str) and insert (inserts new_str \
            after line insert_line; 0 inserts at the top). Relative paths are under /workspace.""")
    public synchronized String editFile(
            @LlmTool.Param(description = "view, create, str_replace or insert") @Nullable String command,
            @LlmTool.Param(description = "The file or directory") @Nullable String path,
            @LlmTool.Param(description = "view: the first and last line to show, e.g. [100, 200]", required = false) @Nullable List<Integer> viewRange,
            @LlmTool.Param(description = "create: the whole content of the file", required = false) @Nullable String fileText,
            @LlmTool.Param(description = "str_replace: the exact text to replace", required = false) @Nullable String oldStr,
            @LlmTool.Param(description = "str_replace and insert: the new text", required = false) @Nullable String newStr,
            @LlmTool.Param(description = "insert: the line after which new_str goes", required = false) @Nullable Integer insertLine) {
        active.run();
        if (command == null || path == null || path.isBlank()) return "edit_file requires a 'command' and a 'path'.";
        String file = absolute(path);
        try {
            return switch (command) {
                case "view" -> view(file, viewRange);
                case "create" -> {
                    if (fileText == null) yield "create requires 'file_text'.";
                    sandboxes.write(tenant, actor, conversation, file, fileText.getBytes(StandardCharsets.UTF_8));
                    yield "Created " + file + ".";
                }
                case "str_replace" -> {
                    if (oldStr == null || oldStr.isEmpty() || newStr == null) yield "str_replace requires 'old_str' and 'new_str'.";
                    String text = text(file);
                    int first = text.indexOf(oldStr);
                    if (first < 0) yield "old_str was not found in " + file + "; view the file and copy the passage exactly.";
                    if (text.indexOf(oldStr, first + 1) >= 0)
                        yield "old_str occurs more than once in " + file + "; include more surrounding lines so it is unique.";
                    sandboxes.write(tenant, actor, conversation, file, (text.substring(0, first) + newStr
                            + text.substring(first + oldStr.length())).getBytes(StandardCharsets.UTF_8));
                    yield "Replaced one passage in " + file + ".";
                }
                case "insert" -> {
                    if (newStr == null || insertLine == null) yield "insert requires 'new_str' and 'insert_line'.";
                    var lines = new ArrayList<>(List.of(text(file).split("\n", -1)));
                    if (insertLine < 0 || insertLine > lines.size()) yield "insert_line must be between 0 and " + lines.size() + ".";
                    lines.add(insertLine, newStr);
                    sandboxes.write(tenant, actor, conversation, file, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
                    yield "Inserted after line " + insertLine + " of " + file + ".";
                }
                default -> "Unknown command '" + command + "'; use view, create, str_replace or insert.";
            };
        } catch (SandboxException unavailable) {
            return failed("edit_file", unavailable.safeMessage());
        } catch (IllegalArgumentException tooLarge) {
            return "The file is larger than " + VIEW_BYTES + " bytes; read parts of it with bash (head, tail, rg).";
        } catch (RuntimeException failure) {
            active.run();
            return failed("edit_file", "The file could not be read or written: " + message(failure));
        }
    }

    @LlmTool(name = "publish_file", description = """
            Delivers a sandbox file to the user: it is saved in their Library and a link is returned. Use this as the \
            final delivery step whenever the task produces a file for the user. Paths inside the sandbox are not \
            reachable by the user and must never be returned. If this tool fails, fix the problem and retry; never \
            claim that the file was delivered.""")
    public synchronized String publishFile(
            @LlmTool.Param(description = "The file in the sandbox") @Nullable String path,
            @LlmTool.Param(description = "The name the user sees, with its extension; defaults to the file's name", required = false) @Nullable String filename) {
        active.run();
        if (path == null || path.isBlank()) return "publish_file requires a 'path'.";
        String file = absolute(path);
        String name = RunPythonTool.safeName(filename == null || filename.isBlank()
                ? file.substring(file.lastIndexOf('/') + 1) : filename);
        publish(call -> ChatCodeEvent.running(call, file, null));
        try {
            byte[] bytes = sandboxes.read(tenant, actor, conversation, file, PUBLISH_BYTES);
            active.run();
            String mediaType = RunPythonTool.mediaType(name);
            UUID id = artifacts.store(tenant, messageId, name, mediaType, bytes);
            publish(call -> ChatCodeEvent.completed(call, List.of(new ChatCodeEvent.GeneratedFile(id, name, mediaType,
                    bytes.length))));
            return "Published " + name + ": file_link /api/chat/file-artifacts/" + id + "/content";
        } catch (ChatException refused) {
            if (!"CHAT_STORAGE_FULL".equals(refused.code())) throw refused;
            publish(ChatCodeEvent::failed);
            return "The user's file library is full; tell the user to free space in their library.";
        } catch (SandboxException unavailable) {
            return failed("publish_file", unavailable.safeMessage());
        } catch (IllegalArgumentException tooLarge) {
            publish(ChatCodeEvent::failed);
            return "The file is larger than " + PUBLISH_BYTES + " bytes and cannot be published.";
        } catch (RuntimeException failure) {
            active.run();
            return failed("publish_file", "The file could not be published: " + message(failure));
        }
    }

    private String view(String file, @Nullable List<Integer> range) {
        var kind = run("[ -d %s ] && echo d || { [ -f %s ] && echo f || echo n; }".formatted(quote(file), quote(file)),
                WRAPPER_MARGIN).stdout().strip();
        if (kind.equals("n")) return file + " does not exist.";
        if (kind.equals("d")) {
            var listing = new StringBuilder(file).append(":\n");
            for (SandboxEntry entry : sandboxes.list(tenant, actor, conversation, file, 2))
                listing.append(entry.directory() ? entry.path() + "/" : entry.path() + " (" + entry.sizeBytes() + " bytes)")
                        .append('\n');
            return listing.toString();
        }
        String[] lines = text(file).split("\n", -1);
        int first = range != null && !range.isEmpty() ? Math.max(1, range.getFirst()) : 1;
        int last = range != null && range.size() > 1 && range.get(1) > 0 ? Math.min(lines.length, range.get(1))
                : Math.min(lines.length, first + VIEW_LINES - 1);
        var shown = new StringBuilder();
        for (int line = first; line <= last; line++) shown.append(line).append('\t').append(lines[line - 1]).append('\n');
        if (range == null && last < lines.length)
            shown.append("[").append(lines.length - last).append(" more lines; pass view_range]\n");
        return shown.toString();
    }

    private String text(String file) {
        return new String(sandboxes.read(tenant, actor, conversation, file, VIEW_BYTES), StandardCharsets.UTF_8);
    }

    /** The whole output when it fits; otherwise its head and tail and the path holding all of it. */
    private String output(String file, long bytes) {
        if (bytes == 0) return "";
        if (bytes <= OUTPUT_CHARACTERS) return text(file);
        int half = OUTPUT_CHARACTERS / 2;
        String head = run("head -c %d %s".formatted(half, file), WRAPPER_MARGIN).stdout();
        String tail = run("tail -c %d %s".formatted(half, file), WRAPPER_MARGIN).stdout();
        return head + "\n... [" + (bytes - 2L * half) + " bytes omitted; all of it is in " + file + "] ...\n" + tail;
    }

    private SandboxCommandResult run(String script, Duration timeout) {
        return sandboxes.run(tenant, actor, conversation,
                new SandboxCommand(script, SandboxService.WORKSPACE, SandboxService.USER, timeout));
    }

    private static Duration timeout(@Nullable Integer seconds) {
        if (seconds == null || seconds <= 0) return DEFAULT_TIMEOUT;
        return Duration.ofSeconds(Math.min(seconds, MAX_TIMEOUT.toSeconds()));
    }

    private static String absolute(String path) {
        String trimmed = path.strip();
        return trimmed.startsWith("/") ? trimmed : SandboxService.WORKSPACE + "/" + trimmed;
    }

    /** A path as one single-quoted shell word. */
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String shown(String text) {
        return text.length() <= ChatCodeEvent.MAX_OUTPUT_CHARACTERS ? text : text.substring(0, ChatCodeEvent.MAX_OUTPUT_CHARACTERS);
    }

    private String failed(String tool, String message) {
        activity.fail();
        publish(ChatCodeEvent::failed);
        LOG.atWarn().addKeyValue("event", "chat.sandbox.tool_failed").addKeyValue("tool", tool).log("Sandbox tool failed");
        return message;
    }

    private static String message(RuntimeException failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /** Publishes timeline progress for the call in flight; a call outside an inspected run publishes nothing. */
    private void publish(Function<String, ChatCodeEvent> event) {
        var call = activity.current();
        if (call != null) events.accept(event.apply(call.id()));
    }
}
