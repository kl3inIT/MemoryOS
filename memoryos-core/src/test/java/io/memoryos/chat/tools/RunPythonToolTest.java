package io.memoryos.chat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.chat.ChatCodeEvent;
import io.memoryos.chat.ChatToolActivity;
import io.memoryos.chat.ChatToolEvent;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.library.UserFile;
import io.memoryos.library.UserFileContentService;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.retrieval.DocumentOriginalService;
import io.memoryos.retrieval.SearchHit;
import io.memoryos.sandbox.SandboxCommand;
import io.memoryos.sandbox.SandboxCommandResult;
import io.memoryos.sandbox.SandboxException;
import io.memoryos.sandbox.SandboxService;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MEM-271: run_python's script runs for real in bash and python3, with {@code /workspace} mapped to a temporary
 * directory, as {@link SandboxToolsTest} runs the Sandbox tools: staging, the last-line wrapper, the files a run
 * writes, captured charts and the result JSON are exercised as the sandbox runs them.
 */
@EnabledOnOs(OS.LINUX)
class RunPythonToolTest {
    private final InterpreterService artifacts = mock(InterpreterService.class);
    private final UserFileContentService files = mock(UserFileContentService.class);
    private final ChatToolActivity activity = mock(ChatToolActivity.class);
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID conversation = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();
    private final List<UserFile> attached = new ArrayList<>();
    private final List<ChatCodeEvent> published = new ArrayList<>();
    @TempDir
    Path root;
    @TempDir
    Path streams;
    private SandboxService sandboxes;

    @BeforeEach
    void setUp() {
        sandboxes = mock(SandboxService.class);
        when(sandboxes.run(eq(tenant), eq(actor), eq(conversation), any())).thenAnswer(call -> run(call.getArgument(3)));
        doAnswer(call -> {
            write(call.getArgument(3), call.<byte[]>getArgument(4));
            return null;
        }).when(sandboxes).write(eq(tenant), eq(actor), eq(conversation), anyString(), any(byte[].class));
        doAnswer(call -> {
            write(call.getArgument(3), call.<InputStream>getArgument(4).readAllBytes());
            return null;
        }).when(sandboxes).writeStream(eq(tenant), eq(actor), eq(conversation), anyString(), any(InputStream.class));
        when(sandboxes.read(eq(tenant), eq(actor), eq(conversation), anyString(), anyLong())).thenAnswer(call -> {
            byte[] bytes = Files.readAllBytes(local(call.getArgument(3)));
            if (bytes.length > (long) call.getArgument(4)) throw new IllegalArgumentException("too large");
            return bytes;
        });
        when(activity.current()).thenReturn(new ChatToolEvent.Call("call-1", "run_python"));
    }

    private RunPythonTool tool() {
        when(files.readable(eq(actor), eq(tenant), any())).thenReturn(attached);
        var ids = attached.stream().map(UserFile::id).toList();
        var workspace = new SandboxTools(sandboxes, artifacts, files, ids, tenant, actor, conversation, messageId,
                () -> {}, activity, published::add);
        return new RunPythonTool(workspace, artifacts, files, actor, tenant, messageId, ids, () -> {}, activity, published::add);
    }

    private UserFile attach(String name, long size, int minutesAgo) {
        var file = new UserFile(UUID.randomUUID(), name, "text/csv", size, UserFile.Status.READY,
                Instant.now().minusSeconds(60L * minutesAgo), Instant.now(), null);
        attached.add(file);
        when(files.open(actor, tenant, file.id())).thenAnswer(ignored -> content(("x".repeat((int) Math.min(size, 16)))
                .getBytes(StandardCharsets.UTF_8)));
        return file;
    }

    private static ObjectContent content(byte[] bytes) {
        var content = mock(ObjectContent.class);
        when(content.metadata()).thenReturn(new ObjectMetadata(bytes.length, "text/csv", new ContentSha256("a".repeat(64))));
        when(content.inputStream()).thenReturn(new ByteArrayInputStream(bytes));
        return content;
    }

    private static JsonNode result(String reply) {
        int reminder = reply.indexOf("\n\n");
        return new ObjectMapper().readTree(reminder < 0 ? reply : reply.substring(0, reminder));
    }

    @Test void stagesAttachmentsUnderAttachmentsAndReturnsTheOnyxResult() {
        attach("old.csv", 3, 30);
        attach("new.csv", 3, 1);

        var reply = tool().runPython("import os\nprint(sorted(os.listdir('attachments')), os.getcwd() == '" + root + "')");

        var json = result(reply);
        assertEquals("python_execution", json.path("type").asString());
        assertEquals("['new.csv', 'old.csv'] True\n", json.path("stdout").asString());
        assertEquals(0, json.path("exit_code").asInt());
        assertTrue(json.path("error").isNull());
        assertTrue(json.path("staging_notice").isNull());
        assertFalse(reply.contains(RunPythonTool.FILE_REMINDER));
    }

    @Test void aBareLastExpressionPrintsAsInJupyter() {
        assertEquals("42\n", result(tool().runPython("x = 40\nx + 2")).path("stdout").asString());
        assertEquals("", result(tool().runPython("x = 40")).path("stdout").asString());
    }

    @Test void searchedSourceFilesAreStagedUnderSearchThroughCitationAuthority() {
        attach("notes.csv", 3, 5);
        var originals = mock(DocumentOriginalService.class);
        UUID document = UUID.randomUUID(), generation = UUID.randomUUID();
        var stored = new StoredObjectId(UUID.randomUUID());
        when(originals.citationOriginals(eq(actor), any())).thenReturn(Map.of(document,
                new StoredObjectReference(stored, new ObjectKey("raw/r"),
                        "report.xlsx", new ObjectMetadata(4, "application/vnd.ms-excel", new ContentSha256("c".repeat(64))))));
        var closed = new AtomicBoolean();
        when(originals.citationOriginal(actor, document, generation)).thenReturn(new DocumentOriginalService
                .Original(null, null, new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)), () -> closed.set(true)));
        var sandbox = new SandboxDocuments(originals, actor);
        sandbox.register(List.of(new SearchHit(document, generation, 0, "Report", "text/plain", "x", "[]",
                Instant.EPOCH, .5)));

        var reply = tool().withSandbox(sandbox).runPython("print(open('search/Report_" + stored.value() + ".xlsx').read())");

        assertEquals("data\n", result(reply).path("stdout").asString());
        assertTrue(Files.exists(root.resolve("attachments/notes.csv")));
        verify(originals).citationOriginal(actor, document, generation);
        assertTrue(closed.get());
    }

    @Test void referencedFilesWinTheFileCapAndTheNoticeNamesTheLimit() {
        attach("budget.xlsx", 3, 100);
        for (int i = 0; i < RunPythonTool.MAX_STAGED_FILES; i++) attach("f" + i + ".csv", 3, 50 - i);

        var reply = tool().runPython("# pd.read_excel('budget.xlsx')\npass");

        assertTrue(Files.exists(root.resolve("attachments/budget.xlsx")));
        assertFalse(Files.exists(root.resolve("attachments/f0.csv")));
        assertEquals("1 of 26 session files were not staged due to per-execution limits (25 files / 104857600 bytes); "
                + "files referenced in the code were prioritized.", result(reply).path("staging_notice").asString());
    }

    @Test void theByteBudgetStillStagesAtLeastOneFile() {
        attach("huge.parquet", RunPythonTool.MAX_STAGED_BYTES + 1, 1);
        attach("small.csv", 3, 2);

        tool().runPython("pass");

        assertTrue(Files.exists(root.resolve("attachments/huge.parquet")));
        assertFalse(Files.exists(root.resolve("attachments/small.csv")));
    }

    @Test void aFileAlreadyInTheWorkspaceIsNotCopiedAgain() {
        var file = attach("data.csv", 3, 1);

        tool().runPython("pass");
        tool().runPython("pass");

        verify(files, times(1)).open(actor, tenant, file.id());
    }

    @Test void filesTheRunWritesOrChangesAreStoredAndLinkedAndOlderOnesAreNot() {
        var artifact = UUID.randomUUID();
        when(artifacts.store(eq(tenant), eq(messageId), eq("report.xlsx"),
                eq("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), any())).thenReturn(artifact);

        var reply = tool().runPython("import os\nos.makedirs('out')\nopen('out/report.xlsx', 'wb').write(b'\\x01\\x02')");

        var file = result(reply).path("generated_files").get(0);
        assertEquals("report.xlsx", file.path("filename").asString());
        assertEquals("/api/chat/file-artifacts/" + artifact + "/content", file.path("file_link").asString());
        assertTrue(reply.endsWith(RunPythonTool.FILE_REMINDER));
        verify(artifacts).store(tenant, messageId, "report.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2});
        // The next call leaves the file as it is: it stays for later calls and bash, and is not handed over again.
        var again = tool().runPython("print(open('out/report.xlsx', 'rb').read())");
        assertEquals(0, result(again).path("generated_files").size());
        assertEquals("b'\\x01\\x02'\n", result(again).path("stdout").asString());
    }

    @Test void capturedFiguresBecomeChartsApartFromTheModelsFiles() {
        var chart = UUID.randomUUID();
        var plain = UUID.randomUUID();
        String line = "{\"type\":\"line\",\"title\":\"Doanh thu quý 3\",\"elements\":[]}";
        when(artifacts.store(tenant, messageId, "Doanh thu quý 3.png", "image/png", new byte[]{1}, line)).thenReturn(chart);
        when(artifacts.store(tenant, messageId, "chart-2.png", "image/png", new byte[]{2}, null)).thenReturn(plain);

        // What the image's sitecustomize writes at exit; the second chart's data is not a chart object.
        var reply = tool().runPython("""
                import os
                os.makedirs('.memoryos-charts')
                open('.memoryos-charts/chart-1.png', 'wb').write(b'\\x01')
                open('.memoryos-charts/chart-1.json', 'w').write('%s')
                open('.memoryos-charts/chart-2.png', 'wb').write(b'\\x02')
                open('.memoryos-charts/chart-2.json', 'w').write('[1,2]')
                open('.memoryos-charts/other.txt', 'w').write('x')
                """.formatted(line));

        var json = result(reply);
        assertEquals(0, json.path("generated_files").size());
        assertFalse(reply.contains(RunPythonTool.FILE_REMINDER));
        assertEquals("line", json.path("charts").get(0).path("type").asString());
        assertEquals("Doanh thu quý 3", json.path("charts").get(0).path("title").asString());
        assertEquals("/api/chat/file-artifacts/" + chart + "/content", json.path("charts").get(0).path("file_link").asString());
        assertEquals("image", json.path("charts").get(1).path("type").asString());
        assertFalse(reply.contains("elements"));
        assertEquals(List.of(new ChatCodeEvent.GeneratedFile(chart, "Doanh thu quý 3.png", "image/png", 1, true),
                new ChatCodeEvent.GeneratedFile(plain, "chart-2.png", "image/png", 1, false)), published.getLast().files());
        assertFalse(Files.exists(root.resolve(".memoryos-charts")));
    }

    @Test void chartDataMustBeABoundedObjectWithAType() {
        assertEquals("{\"type\":\"pie\"}", RunPythonTool.chartJson("{\"type\": \"pie\"}".getBytes()));
        assertEquals(null, RunPythonTool.chartJson("{\"title\":\"x\"}".getBytes()));
        assertEquals(null, RunPythonTool.chartJson("not json".getBytes()));
        assertEquals(null, RunPythonTool.chartJson(new byte[RunPythonTool.MAX_CHART_JSON_BYTES + 1]));
    }

    @Test void oversizedGeneratedFilesAreReportedAndNotStored() {
        var reply = tool().runPython("open('big.csv', 'wb').truncate(" + (RunPythonTool.MAX_GENERATED_BYTES + 1) + ")");

        assertTrue(result(reply).path("staging_notice").asString().contains("big.csv"));
        verify(artifacts, never()).store(any(), any(), any(), any(), any());
    }

    @Test void failedRunsReportStderrAsTheErrorAndTruncateOutput() {
        var json = result(tool().runPython("print('x' * " + (RunPythonTool.MAX_OUTPUT_CHARACTERS + 6) + ")\nraise ValueError('boom')"));

        assertEquals(1, json.path("exit_code").asInt());
        assertTrue(json.path("error").asString().contains("ValueError: boom"), json.path("error").asString());
        assertTrue(json.path("stdout").asString().endsWith("\n... [output truncated, 7 characters omitted]"));
    }

    @Test void exitCode124IsATimeout() {
        var json = result(tool().runPython("import sys\nsys.exit(124)"));

        assertTrue(json.path("timed_out").asBoolean());
        assertTrue(json.path("exit_code").isNull());
        assertEquals(ChatCodeEvent.Stage.FAILED, published.getLast().stage());
    }

    @Test void aSandboxThatCannotRunReturnsExitMinusOneWithItsMessageLikeOnyx() {
        when(sandboxes.run(eq(tenant), eq(actor), eq(conversation), any())).thenThrow(SandboxException.busy(3));

        var json = result(tool().runPython("print(1)"));

        assertEquals(-1, json.path("exit_code").asInt());
        assertEquals("Every sandbox is in use. Try again in a few minutes.", json.path("error").asString());
        assertEquals(json.path("error").asString(), json.path("stderr").asString());
        verify(activity).fail();
        var error = published.get(published.size() - 2);
        assertEquals(ChatCodeEvent.Stage.OUTPUT, error.stage());
        assertEquals("stderr", error.stream());
        assertEquals(ChatCodeEvent.Stage.FAILED, published.getLast().stage());
    }

    @Test void missingCodeGetsTheOnyxMessage() {
        assertEquals(RunPythonTool.MISSING_CODE, tool().runPython(" "));
    }

    @Test void theTimelineGetsTheCodeItsOutputAndTheGeneratedFiles() {
        var artifact = UUID.randomUUID();
        when(artifacts.store(eq(tenant), eq(messageId), eq("chart.png"), eq("image/png"), any())).thenReturn(artifact);

        tool().runPython("import sys\nprint('first')\nprint('oops', file=sys.stderr)\nwith open('chart.png', 'wb') as out:\n    out.write(b'\\x09')");

        assertEquals(ChatCodeEvent.Stage.RUNNING, published.getFirst().stage());
        assertTrue(published.getFirst().code().startsWith("import sys"));
        var output = published.stream().filter(event -> event.stage() == ChatCodeEvent.Stage.OUTPUT).toList();
        assertEquals(List.of("stdout", "stderr"), output.stream().map(ChatCodeEvent::stream).toList());
        assertEquals("first\n", output.getFirst().output());
        var completed = published.getLast();
        assertEquals(ChatCodeEvent.Stage.COMPLETED, completed.stage());
        assertEquals(List.of(new ChatCodeEvent.GeneratedFile(artifact, "chart.png", "image/png", 1)), completed.files());
    }

    @Test void namesAreSanitizedAndDeduplicatedLikeOnyx() {
        assertEquals("a_b_c.csv", RunPythonTool.safeName("a/b:c.csv"));
        assertEquals("file", RunPythonTool.safeName(" .. "));
        // Python " ..csv".strip().strip(".") is "csv", which has no extension.
        assertEquals("csv", RunPythonTool.safeName(" ..csv"));
        assertEquals("report", RunPythonTool.safeName("  .report.  "));
        // A long name keeps its extension, as Onyx trims the base only.
        String longName = RunPythonTool.safeName("b".repeat(300) + ".xlsx");
        assertEquals(RunPythonTool.FILENAME_LIMIT, longName.length());
        assertTrue(longName.endsWith(".xlsx"));
        var used = new HashSet<String>();
        assertEquals("data.csv", RunPythonTool.dedupe("data.csv", "id1", used));
        assertEquals("data_id2.csv", RunPythonTool.dedupe("data.csv", "id2", used));
        String dedupedLong = RunPythonTool.dedupe(longName, "4f0c2d1e-0000-4000-8000-000000000001", used);
        String repeatedLong = RunPythonTool.dedupe(longName, "4f0c2d1e-0000-4000-8000-000000000002", used);
        assertEquals(longName, dedupedLong);
        assertEquals(RunPythonTool.FILENAME_LIMIT, repeatedLong.length());
        assertTrue(repeatedLong.endsWith("_4f0c2d1e-0000-4000-8000-000000000002.xlsx"));
    }

    private void write(String path, byte[] bytes) throws IOException {
        Path file = local(path);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** The sandbox path mapped to the temporary directory. */
    private Path local(String path) {
        return Path.of(path.replace(SandboxService.WORKSPACE, root.toString()));
    }

    private SandboxCommandResult run(SandboxCommand command) {
        try {
            Path out = Files.createTempFile(streams, "out", ".txt");
            Path err = Files.createTempFile(streams, "err", ".txt");
            var process = new ProcessBuilder("bash", "-c", command.command().replace(SandboxService.WORKSPACE, root.toString()))
                    .directory(root.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            long began = System.nanoTime();
            boolean ended = process.waitFor(command.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!ended) process.destroyForcibly().waitFor();
            String stdout = Files.readString(out).replace(root.toString(), SandboxService.WORKSPACE);
            return new SandboxCommandResult(ended ? process.exitValue() : -1, stdout, Files.readString(err).strip(), !ended,
                    Duration.ofNanos(System.nanoTime() - began));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
