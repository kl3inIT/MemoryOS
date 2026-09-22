package io.memoryos.chat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.chat.ChatFileContentService;
import io.memoryos.chat.ChatToolActivity;
import io.memoryos.chat.UserFile;
import io.memoryos.chat.interpreter.InterpreterClient;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectMetadata;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RunPythonToolTest {
    private final InterpreterClient client = mock(InterpreterClient.class);
    private final InterpreterService artifacts = mock(InterpreterService.class);
    private final ChatFileContentService files = mock(ChatFileContentService.class);
    private final ChatToolActivity activity = mock(ChatToolActivity.class);
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID messageId = UUID.randomUUID();
    private final List<UserFile> attached = new ArrayList<>();
    private final List<io.memoryos.chat.ChatCodeEvent> published = new ArrayList<>();

    private RunPythonTool tool() {
        when(files.readable(eq(actor), eq(tenant), any())).thenReturn(attached);
        when(activity.current()).thenReturn(new io.memoryos.chat.ChatToolEvent.Call("call-1", "run_python"));
        return new RunPythonTool(client, artifacts, files, actor, tenant, messageId, attached.stream().map(UserFile::id).toList(),
                () -> {}, activity, published::add);
    }

    private UserFile attach(String name, long size, int minutesAgo) throws IOException {
        var file = new UserFile(UUID.randomUUID(), name, "text/csv", size, UserFile.Status.READY,
                Instant.now().minusSeconds(60L * minutesAgo), Instant.now(), null);
        attached.add(file);
        when(files.open(actor, tenant, file.id())).thenAnswer(ignored -> content(name, size));
        when(client.upload(eq(RunPythonTool.safeName(name)), anyString(), any())).thenReturn("svc-" + name);
        return file;
    }

    private static ObjectContent content(String name, long size) {
        var content = mock(ObjectContent.class);
        when(content.metadata()).thenReturn(new ObjectMetadata(size, "text/csv",
                new ContentSha256(String.format("%064x", Math.abs(name.hashCode())))));
        when(content.inputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        return content;
    }

    private static InterpreterClient.Execution ok(String stdout, InterpreterClient.WorkspaceFile... generated) {
        return new InterpreterClient.Execution(stdout, "", 0, false, List.of(generated));
    }

    private static JsonNode result(String reply) {
        int reminder = reply.indexOf("\n\n");
        return new ObjectMapper().readTree(reminder < 0 ? reply : reply.substring(0, reminder));
    }

    @SuppressWarnings("unchecked")
    private List<InterpreterClient.StagedFile> staged() throws IOException {
        ArgumentCaptor<List<InterpreterClient.StagedFile>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).executeStream(anyString(), anyInt(), captor.capture(), any());
        return captor.getValue();
    }

    @Test void stagesAttachmentsChronologicallyAndReturnsTheOnyxResult() throws Exception {
        attach("old.csv", 10, 30);
        attach("new.csv", 10, 1);
        when(client.executeStream(anyString(), anyInt(), anyList(), any())).thenReturn(ok("42\n"));

        var reply = tool().runPython("print(42)");

        assertEquals(List.of(new InterpreterClient.StagedFile("old.csv", "svc-old.csv"),
                new InterpreterClient.StagedFile("new.csv", "svc-new.csv")), staged());
        var json = result(reply);
        assertEquals("python_execution", json.path("type").asString());
        assertEquals("42\n", json.path("stdout").asString());
        assertEquals(0, json.path("exit_code").asInt());
        assertTrue(json.path("error").isNull());
        assertTrue(json.path("staging_notice").isNull());
        assertFalse(reply.contains(RunPythonTool.FILE_REMINDER));
    }

    @Test void searchedSourceFilesAreStagedAfterTheChatFilesThroughCitationAuthority() throws Exception {
        attach("notes.csv", 10, 5);
        var originals = mock(io.memoryos.retrieval.DocumentOriginalService.class);
        UUID document = UUID.randomUUID(), generation = UUID.randomUUID();
        var stored = new io.memoryos.objectstorage.StoredObjectId(UUID.randomUUID());
        when(originals.citationOriginals(eq(actor), any())).thenReturn(java.util.Map.of(document,
                new io.memoryos.objectstorage.StoredObjectReference(stored, new io.memoryos.objectstorage.ObjectKey("raw/r"),
                        "report.xlsx", new ObjectMetadata(20, "application/vnd.ms-excel", new ContentSha256("c".repeat(64))))));
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        when(originals.citationOriginal(actor, document, generation)).thenReturn(new io.memoryos.retrieval.DocumentOriginalService
                .Original(null, null, new ByteArrayInputStream(new byte[20]), () -> closed.set(true)));
        var sandbox = new SandboxDocuments(originals, actor);
        sandbox.register(List.of(new io.memoryos.retrieval.SearchHit(document, generation, 0, "Report", "text/plain", "x", "[]",
                Instant.EPOCH, .5)));
        String name = "Report_" + stored.value() + ".xlsx";
        when(client.upload(eq(name), eq("application/vnd.ms-excel"), any())).thenReturn("svc-report");
        when(client.executeStream(anyString(), anyInt(), anyList(), any())).thenReturn(ok(""));

        tool().withSandbox(sandbox).runPython("print(1)");

        assertEquals(List.of(new InterpreterClient.StagedFile("notes.csv", "svc-notes.csv"),
                new InterpreterClient.StagedFile(name, "svc-report")), staged());
        verify(originals).citationOriginal(actor, document, generation);
        assertTrue(closed.get());
    }

    @Test void referencedFilesWinTheFileCapAndTheNoticeNamesTheLimit() throws Exception {
        var referenced = attach("budget.xlsx", 10, 100);
        for (int i = 0; i < RunPythonTool.MAX_STAGED_FILES; i++) attach("f" + i + ".csv", 10, 50 - i);
        when(client.executeStream(anyString(), anyInt(), anyList(), any())).thenReturn(ok(""));

        var reply = tool().runPython("import pandas as pd\npd.read_excel('budget.xlsx')");

        var staged = staged();
        assertEquals(RunPythonTool.MAX_STAGED_FILES, staged.size());
        assertEquals(referenced.filename(), staged.getFirst().path());
        assertEquals("1 of 26 session files were not staged due to per-execution limits (25 files / 104857600 bytes); "
                + "files referenced in the code were prioritized.", result(reply).path("staging_notice").asString());
    }

    @Test void theByteBudgetStillStagesAtLeastOneFile() throws Exception {
        attach("huge.parquet", RunPythonTool.MAX_STAGED_BYTES + 1, 1);
        attach("small.csv", 10, 2);
        when(client.executeStream(anyString(), anyInt(), anyList(), any())).thenReturn(ok(""));

        tool().runPython("print(1)");

        assertEquals(List.of("huge.parquet"), staged().stream().map(InterpreterClient.StagedFile::path).toList());
    }

    @Test void uploadsAreReusedWithinTheTurn() throws Exception {
        attach("data.csv", 10, 1);
        when(client.executeStream(anyString(), anyInt(), anyList(), any())).thenReturn(ok(""));
        var tool = tool();

        tool.runPython("print(1)");
        tool.runPython("print(2)");

        verify(client, times(1)).upload(eq("data.csv"), anyString(), any());
    }

    @Test void generatedFilesAreStoredLinkedAndDeletedFromTheService() throws Exception {
        var artifact = UUID.randomUUID();
        when(client.executeStream(anyString(), anyInt(), anyList(), any())).thenReturn(ok("",
                new InterpreterClient.WorkspaceFile("out/report.xlsx", "file", "11111111-1111-1111-1111-111111111111"),
                new InterpreterClient.WorkspaceFile("out", "directory", null)));
        when(client.download("11111111-1111-1111-1111-111111111111")).thenReturn(new byte[]{1, 2});
        when(artifacts.store(tenant, messageId, "report.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1, 2})).thenReturn(artifact);

        var reply = tool().runPython("df.to_excel('out/report.xlsx')");

        var file = result(reply).path("generated_files").get(0);
        assertEquals("report.xlsx", file.path("filename").asString());
        assertEquals("/api/chat/file-artifacts/" + artifact + "/content", file.path("file_link").asString());
        assertTrue(reply.endsWith(RunPythonTool.FILE_REMINDER));
        assertFalse(reply.contains("11111111-1111-1111-1111-111111111111"));
        verify(client).delete("11111111-1111-1111-1111-111111111111");
    }

    @Test void capturedFiguresBecomeChartsApartFromTheModelsFilesAndServiceCopiesAreDeleted() throws Exception {
        var chart = UUID.randomUUID();
        var plain = UUID.randomUUID();
        String line = "{\"type\":\"line\",\"title\":\"Doanh thu quý 3\",\"elements\":[]}";
        when(client.executeStream(anyString(), anyInt(), anyList(), any())).thenReturn(ok("",
                new InterpreterClient.WorkspaceFile(".memoryos-charts", "directory", null),
                new InterpreterClient.WorkspaceFile(".memoryos-charts/chart-1.png", "file", "png-1"),
                new InterpreterClient.WorkspaceFile(".memoryos-charts/chart-1.json", "file", "json-1"),
                new InterpreterClient.WorkspaceFile(".memoryos-charts/chart-2.png", "file", "png-2"),
                new InterpreterClient.WorkspaceFile(".memoryos-charts/chart-2.json", "file", "json-2"),
                new InterpreterClient.WorkspaceFile(".memoryos-charts/other.txt", "file", "other")));
        when(client.download("png-1")).thenReturn(new byte[]{1});
        when(client.download("json-1")).thenReturn(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(client.download("png-2")).thenReturn(new byte[]{2});
        // Not a chart object: the PNG is kept without chart data.
        when(client.download("json-2")).thenReturn("[1,2]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(artifacts.store(tenant, messageId, "Doanh thu quý 3.png", "image/png", new byte[]{1}, line)).thenReturn(chart);
        when(artifacts.store(tenant, messageId, "chart-2.png", "image/png", new byte[]{2}, null)).thenReturn(plain);

        var reply = tool().runPython("plt.plot([1, 2])");

        var json = result(reply);
        assertEquals(0, json.path("generated_files").size());
        assertFalse(reply.contains(RunPythonTool.FILE_REMINDER));
        assertEquals("line", json.path("charts").get(0).path("type").asString());
        assertEquals("Doanh thu quý 3", json.path("charts").get(0).path("title").asString());
        assertEquals("/api/chat/file-artifacts/" + chart + "/content", json.path("charts").get(0).path("file_link").asString());
        assertEquals("image", json.path("charts").get(1).path("type").asString());
        assertFalse(reply.contains("elements"));
        for (var id : List.of("png-1", "json-1", "png-2", "json-2", "other")) verify(client).delete(id);
        var terminal = published.getLast();
        assertEquals(List.of(new io.memoryos.chat.ChatCodeEvent.GeneratedFile(chart, "Doanh thu quý 3.png", "image/png", 1, true),
                new io.memoryos.chat.ChatCodeEvent.GeneratedFile(plain, "chart-2.png", "image/png", 1, false)), terminal.files());
    }

    @Test void chartDataMustBeABoundedObjectWithAType() {
        assertEquals("{\"type\":\"pie\"}", RunPythonTool.chartJson("{\"type\": \"pie\"}".getBytes()));
        assertEquals(null, RunPythonTool.chartJson("{\"title\":\"x\"}".getBytes()));
        assertEquals(null, RunPythonTool.chartJson("not json".getBytes()));
        assertEquals(null, RunPythonTool.chartJson(new byte[RunPythonTool.MAX_CHART_JSON_BYTES + 1]));
    }

    @Test void oversizedGeneratedFilesAreReportedAndStillDeleted() throws Exception {
        when(client.executeStream(anyString(), anyInt(), anyList(), any())).thenReturn(ok("",
                new InterpreterClient.WorkspaceFile("big.csv", "file", "22222222-2222-2222-2222-222222222222")));
        when(client.download(anyString())).thenThrow(mock(InterpreterClient.TooLargeException.class));

        var reply = tool().runPython("write big file");

        assertTrue(result(reply).path("staging_notice").asString().contains("big.csv"));
        verify(client).delete("22222222-2222-2222-2222-222222222222");
        verify(artifacts, never()).store(any(), any(), any(), any(), any());
    }

    @Test void failedRunsReportStderrAsTheErrorAndTruncateOutput() throws Exception {
        String longOutput = "x".repeat(RunPythonTool.MAX_OUTPUT_CHARACTERS + 7);
        when(client.executeStream(anyString(), anyInt(), anyList(), any()))
                .thenReturn(new InterpreterClient.Execution(longOutput, "Traceback", 1, false, List.of()));

        var json = result(tool().runPython("raise SystemExit(1)"));

        assertEquals("Traceback", json.path("error").asString());
        assertTrue(json.path("stdout").asString().endsWith("\n... [output truncated, 7 characters omitted]"));
    }

    @Test void aServiceFailureReturnsExitMinusOneWithTheExceptionTextLikeOnyx() throws Exception {
        when(client.executeStream(anyString(), anyInt(), anyList(), any()))
                .thenThrow(new IOException("Code interpreter returned HTTP 503: executor image missing"));

        var reply = tool().runPython("print(1)");

        var json = result(reply);
        assertEquals(-1, json.path("exit_code").asInt());
        assertEquals("Code interpreter returned HTTP 503: executor image missing", json.path("error").asString());
        assertEquals("Code interpreter returned HTTP 503: executor image missing", json.path("stderr").asString());
        verify(activity).fail();
    }

    @Test void missingCodeGetsTheOnyxMessageAndEachCallUsesTheFixedTimeout() throws Exception {
        assertEquals(RunPythonTool.MISSING_CODE, tool().runPython(" "));
        when(client.executeStream(anyString(), anyInt(), anyList(), any())).thenReturn(ok(""));

        tool().runPython("print(1)");

        // A MemoryOS turn has no total deadline, so each call runs with the Onyx per-call timeout.
        var timeout = ArgumentCaptor.forClass(Integer.class);
        verify(client).executeStream(anyString(), timeout.capture(), anyList(), any());
        assertEquals(RunPythonTool.DEFAULT_TIMEOUT_MS, timeout.getValue());
    }

    @Test void theTimelineGetsTheCodeBoundedOutputAndTheGeneratedFiles() throws Exception {
        var artifact = UUID.randomUUID();
        when(client.executeStream(anyString(), anyInt(), anyList(), any())).thenAnswer(call -> {
            InterpreterClient.OutputListener listener = call.getArgument(3);
            listener.output("stdout", "first\n");
            listener.output("stderr", "x".repeat(io.memoryos.chat.ChatCodeEvent.MAX_OUTPUT_CHARACTERS));
            listener.output("stdout", "dropped, the budget is gone");
            return ok("", new InterpreterClient.WorkspaceFile("chart.png", "file", "33333333-3333-3333-3333-333333333333"));
        });
        when(client.download(anyString())).thenReturn(new byte[]{9});
        when(artifacts.store(tenant, messageId, "chart.png", "image/png", new byte[]{9})).thenReturn(artifact);

        tool().runPython("plt.savefig('chart.png')");

        assertEquals(io.memoryos.chat.ChatCodeEvent.Stage.RUNNING, published.getFirst().stage());
        assertEquals("plt.savefig('chart.png')", published.getFirst().code());
        var streamed = published.stream().filter(event -> event.stage() == io.memoryos.chat.ChatCodeEvent.Stage.OUTPUT).toList();
        assertEquals("first\n", streamed.getFirst().output());
        assertEquals(List.of("stdout", "stderr"), streamed.stream().map(io.memoryos.chat.ChatCodeEvent::stream).toList());
        assertEquals(io.memoryos.chat.ChatCodeEvent.MAX_OUTPUT_CHARACTERS,
                streamed.stream().mapToInt(event -> event.output().length()).sum());
        var completed = published.getLast();
        assertEquals(io.memoryos.chat.ChatCodeEvent.Stage.COMPLETED, completed.stage());
        assertEquals(List.of(new io.memoryos.chat.ChatCodeEvent.GeneratedFile(artifact, "chart.png", "image/png", 1)),
                completed.files());
    }

    @Test void aNonZeroExitOrTimeoutIsAFailedStepThatKeepsItsFiles() throws Exception {
        var artifact = UUID.randomUUID();
        when(client.executeStream(anyString(), anyInt(), anyList(), any())).thenReturn(
                new InterpreterClient.Execution("", "ZeroDivisionError", 1, false,
                        List.of(new InterpreterClient.WorkspaceFile("partial.csv", "file", "44444444-4444-4444-4444-444444444444"))));
        when(client.download(anyString())).thenReturn(new byte[]{7});
        when(artifacts.store(tenant, messageId, "partial.csv", "text/csv", new byte[]{7})).thenReturn(artifact);

        tool().runPython("1/0");

        var last = published.getLast();
        assertEquals(io.memoryos.chat.ChatCodeEvent.Stage.FAILED, last.stage());
        assertEquals(List.of(artifact), last.files().stream().map(io.memoryos.chat.ChatCodeEvent.GeneratedFile::id).toList());

        published.clear();
        when(client.executeStream(anyString(), anyInt(), anyList(), any())).thenReturn(
                new InterpreterClient.Execution("", "", null, true, List.of()));
        tool().runPython("while True: pass");
        assertEquals(io.memoryos.chat.ChatCodeEvent.Stage.FAILED, published.getLast().stage());
    }

    @Test void aFailedRunShowsTheErrorOnStderrBeforeTheFailedStage() throws Exception {
        when(client.executeStream(anyString(), anyInt(), anyList(), any())).thenThrow(new IOException("Code interpreter error: boom"));

        tool().runPython("print(1)");

        var error = published.get(published.size() - 2);
        assertEquals(io.memoryos.chat.ChatCodeEvent.Stage.OUTPUT, error.stage());
        assertEquals("stderr", error.stream());
        assertEquals("Code interpreter error: boom", error.output());
        assertEquals(io.memoryos.chat.ChatCodeEvent.Stage.FAILED, published.getLast().stage());
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
}
