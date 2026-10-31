package io.memoryos.chat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.chat.ChatToolActivity;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.library.UserFile;
import io.memoryos.library.UserFileContentService;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.sandbox.SandboxCommand;
import io.memoryos.sandbox.SandboxCommandResult;
import io.memoryos.sandbox.SandboxService;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * MEM-270: the Sandbox tools' scripts run for real in bash, with {@code /workspace} mapped to a temporary directory,
 * so the timeout, the head-and-tail cut, background commands and the editor are exercised as the sandbox runs them.
 */
@EnabledOnOs(OS.LINUX)
class SandboxToolsTest {
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final UUID conversation = UUID.randomUUID();
    private final InterpreterService artifacts = mock(InterpreterService.class);
    @TempDir
    Path root;
    /** The commands' captured streams, outside the workspace so a listing of it does not show them. */
    @TempDir
    Path streams;
    private final UserFileContentService files = mock(UserFileContentService.class);
    private final UUID attachment = UUID.randomUUID();
    private SandboxService sandboxes;
    private SandboxTools tools;

    @BeforeEach
    void setUp() {
        sandboxes = mock(SandboxService.class);
        when(sandboxes.run(eq(tenant), eq(actor), eq(conversation), any())).thenAnswer(call -> run(call.getArgument(3)));
        doAnswer(call -> {
            Path file = local(call.getArgument(3));
            Files.createDirectories(file.getParent());
            Files.write(file, (byte[]) call.getArgument(4));
            return null;
        }).when(sandboxes).write(eq(tenant), eq(actor), eq(conversation), anyString(), any());
        when(sandboxes.read(eq(tenant), eq(actor), eq(conversation), anyString(), anyLong())).thenAnswer(call -> {
            byte[] bytes = Files.readAllBytes(local(call.getArgument(3)));
            if (bytes.length > (long) call.getArgument(4)) throw new IllegalArgumentException("too large");
            return bytes;
        });
        tools = tools(List.of());
    }

    private SandboxTools tools(List<UUID> fileIds) {
        return new SandboxTools(sandboxes, artifacts, files, fileIds, tenant, actor, conversation, UUID.randomUUID(),
                () -> {}, new ChatToolActivity(ignored -> {}), ignored -> {});
    }

    @Test
    void theConversationsAttachmentsAreInTheWorkspaceOnceATurn() {
        byte[] sheet = "Ngày,Doanh thu\n2026-01-03,120000\n".getBytes(StandardCharsets.UTF_8);
        var file = new UserFile(attachment, "doanh thu/2026.csv", "text/csv", sheet.length, UserFile.Status.READY,
                Instant.parse("2026-10-16T00:00:00Z"), Instant.parse("2026-10-16T00:00:00Z"), null);
        when(files.readable(actor, tenant, List.of(attachment))).thenReturn(List.of(file));
        when(files.open(actor, tenant, attachment)).thenAnswer(call -> content(sheet));
        var withFile = tools(List.of(attachment));
        String first = withFile.bash("ls attachments; tail -1 attachments/*", null, null, null);
        assertTrue(first.contains("doanh thu_2026.csv") && first.contains("2026-01-03,120000"), first);
        withFile.bash("true", null, null, null);
        // A later turn finds the copy in place and does not send it again.
        tools(List.of(attachment)).bash("true", null, null, null);
        verify(files, times(1)).open(actor, tenant, attachment);
    }

    private static ObjectContent content(byte[] bytes) {
        var stream = new ByteArrayInputStream(bytes);
        var content = mock(ObjectContent.class);
        when(content.inputStream()).thenReturn(stream);
        return content;
    }

    @Test
    void aCommandAnswersItsExitCodeAndBothStreams() {
        String ok = tools.bash("echo hello; pwd", "Say hello", null, null);
        assertTrue(ok.startsWith("exit_code: 0\n"), ok);
        assertTrue(ok.contains("stdout:\nhello\n" + root), ok);
        String bad = tools.bash("echo oops >&2; exit 3", null, null, null);
        assertTrue(bad.startsWith("exit_code: 3\n") && bad.contains("stderr:\noops"), bad);
    }

    @Test
    void aCommandPastItsTimeoutIsStoppedWithTheOutputItWrote() {
        long began = System.nanoTime();
        String result = tools.bash("echo started; sleep 30; echo never", null, 2, null);
        assertTrue(Duration.ofNanos(System.nanoTime() - began).toSeconds() < 15);
        assertTrue(result.contains("timed out") && result.contains("started") && !result.contains("never"), result);
    }

    @Test
    void longOutputKeepsItsBeginningAndEndAndNamesTheWhole() {
        String result = tools.bash("seq 1 100000", null, null, null);
        assertTrue(result.contains("bytes omitted; all of it is in /workspace/.memoryos/run/"), result.length() > 200 ? result.substring(0, 200) : result);
        assertTrue(result.contains("stdout:\n1\n2\n3\n") && result.strip().endsWith("100000"));
        assertTrue(result.length() < SandboxTools.OUTPUT_CHARACTERS + 1_000);
    }

    @Test
    void aBackgroundCommandIsReadAsItRunsAndAfterItEnds() throws Exception {
        String started = tools.bash("for i in 1 2 3; do echo line $i; sleep 1; done", "Count to three", null, true);
        String id = started.replaceAll("(?s).*bash_id ([0-9a-f-]{36}).*", "$1");
        assertTrue(tools.bashOutput(id, null, null).startsWith("status: running"));
        Thread.sleep(4_500);
        String ended = tools.bashOutput(id, null, null);
        assertTrue(ended.startsWith("status: ended, exit_code 0"), ended);
        assertTrue(ended.contains("line 3"), ended);
        assertEquals("output:\n", tools.bashOutput(id, null, null).replaceAll("(?s)^status:[^\n]*\n", ""));
        assertEquals("Unknown bash_id.", tools.bashOutput("not-an-id", null, null));
    }

    @Test
    void killStopsABackgroundCommandAndItsChildren() {
        String started = tools.bash("sleep 60 & sleep 60; echo unreachable", null, null, true);
        String id = started.replaceAll("(?s).*bash_id ([0-9a-f-]{36}).*", "$1");
        String killed = tools.bashOutput(id, null, true);
        assertTrue(killed.startsWith("status: ended, exit_code 143"), killed);
        String left = tools.bash("pgrep -f 'sleep 6[0]' || echo none", null, null, null);
        assertTrue(left.contains("none"), left);
    }

    @Test
    void theEditorViewsCreatesReplacesOnceAndInserts() {
        assertEquals("Created /workspace/notes/a.txt.", tools.editFile("create", "notes/a.txt", null, "one\ntwo\nthree", null, null, null));
        assertEquals("1\tone\n2\ttwo\n3\tthree\n", tools.editFile("view", "notes/a.txt", null, null, null, null, null));
        assertTrue(tools.editFile("str_replace", "notes/a.txt", null, null, "two", "2", null).startsWith("Replaced"));
        tools.editFile("create", "notes/b.txt", null, "x\nx", null, null, null);
        assertTrue(tools.editFile("str_replace", "notes/b.txt", null, null, "x", "y", null).contains("more than once"));
        assertTrue(tools.editFile("str_replace", "notes/a.txt", null, null, "absent", "y", null).contains("was not found"));
        tools.editFile("insert", "notes/a.txt", null, null, null, "zero", 0);
        assertEquals("1\tzero\n2\tone\n3\t2\n4\tthree\n", tools.editFile("view", "/workspace/notes/a.txt", null, null, null, null, null));
        assertEquals("2\tone\n3\t2\n", tools.editFile("view", "notes/a.txt", List.of(2, 3), null, null, null, null));
        assertTrue(tools.editFile("view", "notes", null, null, null, null, null).startsWith("/workspace/notes:\n"));
        assertTrue(tools.editFile("view", "missing.txt", null, null, null, null, null).contains("does not exist"));
    }

    @Test
    void aPublishedFileIsStoredAndLinked() {
        UUID stored = UUID.randomUUID();
        when(artifacts.store(eq(tenant), any(), eq("Báo cáo.csv"), eq("text/csv"), any())).thenReturn(stored);
        tools.editFile("create", "out/report.csv", null, "a,b\n1,2", null, null, null);
        assertEquals("Published Báo cáo.csv: file_link /api/chat/file-artifacts/" + stored + "/content",
                tools.publishFile("out/report.csv", "Báo cáo.csv"));
    }

    /** The sandbox path mapped to the temporary directory. */
    private Path local(String path) {
        return Path.of(path.replace(SandboxService.WORKSPACE, root.toString()));
    }

    private SandboxCommandResult run(SandboxCommand command) {
        try {
            // Output goes to files, not pipes: a pipe the test reads only after waitFor blocks a chatty command.
            Path out = Files.createTempFile(streams, "out", ".txt");
            Path err = Files.createTempFile(streams, "err", ".txt");
            var process = new ProcessBuilder("bash", "-c", command.command().replace(SandboxService.WORKSPACE, root.toString()))
                    .directory(root.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            long began = System.nanoTime();
            boolean ended = process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!ended) process.destroyForcibly().waitFor();
            String stdout = Files.readString(out).replace(root.toString(), SandboxService.WORKSPACE);
            String stderr = Files.readString(err);
            return new SandboxCommandResult(ended ? process.exitValue() : -1, stdout.endsWith("\n") ? stdout.substring(0, stdout.length() - 1) : stdout,
                    stderr.strip(), !ended, Duration.ofNanos(System.nanoTime() - began));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
