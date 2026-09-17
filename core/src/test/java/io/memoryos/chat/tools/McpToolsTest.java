package io.memoryos.chat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.embabel.agent.api.tool.Tool;
import com.knuddels.jtokkit.api.EncodingType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.memoryos.chat.ChatToolActivity;
import io.memoryos.chat.ChatToolEvent;
import io.memoryos.mcp.McpTurnTools;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

/**
 * The model-facing surface of MCP tools. The reference implementation matches substrings on the exception text
 * and appends the upstream body to the model; these tests pin the opposite for every failure category.
 */
class McpToolsTest {
    private static final UUID SERVER = UUID.randomUUID();
    private final McpTurnTools turn = mock(McpTurnTools.class);
    private final List<ChatToolEvent> events = new ArrayList<>();
    private final MeterRegistry meters = new SimpleMeterRegistry();
    private final McpTurnTools.Binding binding = new McpTurnTools.Binding(SERVER, "drive", "Drive", "search_files",
            "mcp_drive_search_files", "Search files.", "{\"type\":\"object\"}", true);

    private McpTools tools(int maxCalls, int contextTokens) {
        when(turn.bindings()).thenReturn(List.of(binding));
        when(turn.unavailable()).thenReturn(List.of());
        return new McpTools(turn, () -> {}, Duration.ofSeconds(30), maxCalls,
                events::add, new ChatToolActivity(ignored -> {}), () -> contextTokens,
                new JTokkitTokenCountEstimator(EncodingType.O200K_BASE), meters);
    }

    @Test
    void describesEachToolWithItsSnapshotSchemaAndUntrustedWarning() {
        var tool = tools(10, 8000).tools().getFirst().getDefinition();
        assertEquals("mcp_drive_search_files", tool.getName());
        assertEquals("{\"type\":\"object\"}", tool.getInputSchema().toJsonSchema());
        assertTrue(tool.getDescription().contains("untrusted data"));
        assertFalse(tool.getDescription().contains("can change or delete"));

        var writeTool = new McpTools(turn, () -> {}, Duration.ofSeconds(30), 10,
                events::add, new ChatToolActivity(ignored -> {}), () -> 8000,
                new JTokkitTokenCountEstimator(EncodingType.O200K_BASE), meters);
        when(turn.bindings()).thenReturn(List.of(new McpTurnTools.Binding(SERVER, "drive", "Drive", "delete_file",
                "mcp_drive_delete_file", "Delete a file.", "{\"type\":\"object\"}", false)));
        assertTrue(writeTool.tools().getFirst().getDefinition().getDescription()
                .startsWith("This tool can change or delete data."));
    }

    @Test
    void reportsFailureByCategoryWithoutUpstreamText() {
        String upstream = "401 from https://drive.internal?token=SECRET-LEAK";
        for (var reason : McpTurnTools.CallFailure.Reason.values()) {
            var typed = mock(McpTurnTools.class);
            when(typed.bindings()).thenReturn(List.of(binding));
            when(typed.unavailable()).thenReturn(List.of());
            when(typed.call(any(), any(), any())).thenThrow(failure(reason));
            var result = new McpTools(typed, () -> {}, Duration.ofSeconds(30), 10,
                    events::add, new ChatToolActivity(ignored -> {}), () -> 8000,
                    new JTokkitTokenCountEstimator(EncodingType.O200K_BASE), meters)
                    .tools().getFirst().call("{}");
            String message = assertInstanceOf(Tool.Result.Error.class, result).getMessage();
            assertFalse(message.contains("SECRET-LEAK"), reason.name());
            assertFalse(message.contains("drive.internal"), reason.name());
            assertFalse(message.contains("401"), reason.name());
        }
    }

    @Test
    void tellsTheUserToReconnectOnlyForAnAuthorizationFailure() {
        var rejected = mock(McpTurnTools.class);
        when(rejected.bindings()).thenReturn(List.of(binding));
        when(rejected.unavailable()).thenReturn(List.of());
        when(rejected.call(any(), any(), any())).thenThrow(failure(McpTurnTools.CallFailure.Reason.AUTHORIZATION_REQUIRED));
        var rejectedResult = new McpTools(rejected, () -> {}, Duration.ofSeconds(30), 10,
                events::add, new ChatToolActivity(ignored -> {}), () -> 8000,
                new JTokkitTokenCountEstimator(EncodingType.O200K_BASE), meters).tools().getFirst().call("{}");
        String message = assertInstanceOf(Tool.Result.Error.class, rejectedResult).getMessage();
        assertTrue(message.contains("reconnect"));
        assertTrue(message.contains("Drive"));
    }

    @Test
    void stopsCallingAfterTheTurnLimit() {
        when(turn.call(any(), any(), any())).thenReturn(new McpTurnTools.Outcome("ok", false));
        var tool = tools(2, 8000).tools().getFirst();
        assertInstanceOf(Tool.Result.Text.class, tool.call("{}"));
        assertInstanceOf(Tool.Result.Text.class, tool.call("{}"));
        var refused = tool.call("{}");
        assertTrue(assertInstanceOf(Tool.Result.Error.class, refused).getMessage().contains("limit"));
        verify(turn, org.mockito.Mockito.times(2)).call(any(), any(), any());
    }

    @Test
    void capsOneResultAgainstTheRemainingContextAndRejectsUnparseableArguments() {
        when(turn.call(any(), any(), any())).thenReturn(new McpTurnTools.Outcome("x".repeat(200_000), false));
        var capped = assertInstanceOf(Tool.Result.Text.class, tools(10, 500).tools().getFirst().call("{}")).getContent();
        assertTrue(capped.length() < 200_000);
        assertTrue(capped.contains("untrusted data"));

        var invalid = tools(10, 8000).tools().getFirst().call("not json");
        assertInstanceOf(Tool.Result.Error.class, invalid);
        verify(turn, never()).call(any(), eq(Map.of("x", 1)), any());
    }

    @Test
    void namesUnusableServersSoTheModelExplainsTheConnectStep() {
        var pending = mock(McpTurnTools.class);
        when(pending.bindings()).thenReturn(List.of());
        when(pending.unavailable()).thenReturn(List.of(
                new McpTurnTools.Unavailable(SERVER, "Drive", McpTurnTools.Unavailable.Reason.NOT_CONNECTED),
                new McpTurnTools.Unavailable(UUID.randomUUID(), "Jira", McpTurnTools.Unavailable.Reason.REAUTH_REQUIRED)));
        String notice = new McpTools(pending, () -> {}, Duration.ofSeconds(30), 10,
                events::add, new ChatToolActivity(ignored -> {}), () -> 8000,
                new JTokkitTokenCountEstimator(EncodingType.O200K_BASE), meters).unavailableNotice();
        assertTrue(notice.contains("Drive: not connected"));
        assertTrue(notice.contains("Jira: the connection expired"));
        assertEquals("", tools(10, 8000).unavailableNotice());
    }

    @Test
    void recordsOneTimerPerOutcomeWithBoundedLabels() {
        when(turn.call(any(), any(), any())).thenReturn(new McpTurnTools.Outcome("ok", false));
        var tools = tools(1, 8000);
        tools.tools().getFirst().call("{}");
        // Over this turn's limit: refused before the arguments are even parsed, so it counts as call_limit.
        tools.tools().getFirst().call("not json");

        var outcomes = meters.find("memoryos.chat.mcp.call").timers().stream()
                .collect(java.util.stream.Collectors.toMap(
                        timer -> timer.getId().getTag("outcome"), io.micrometer.core.instrument.Timer::count));
        assertEquals(Map.of("succeeded", 1L, "call_limit", 1L), outcomes);

        // Within the limit, unparseable arguments are their own outcome and never reach the server.
        var parsing = new SimpleMeterRegistry();
        new McpTools(turn, () -> {}, Duration.ofSeconds(30), 10, events::add,
                new ChatToolActivity(ignored -> {}), () -> 8000,
                new JTokkitTokenCountEstimator(EncodingType.O200K_BASE), parsing).tools().getFirst().call("not json");
        assertEquals(1, parsing.find("memoryos.chat.mcp.call").tag("outcome", "invalid_arguments").timer().count());

        var sample = meters.find("memoryos.chat.mcp.call").timers().iterator().next().getId();
        // The slug and the snapshotted tool name are bounded; the free-form server name is never a label.
        assertEquals("drive", sample.getTag("server"));
        assertEquals("search_files", sample.getTag("tool"));
        assertEquals(java.util.Set.of("server", "tool", "outcome"),
                sample.getTags().stream().map(io.micrometer.core.instrument.Tag::getKey)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void countsAnAuthorizationFailureUnderItsOwnOutcome() {
        var rejected = mock(McpTurnTools.class);
        when(rejected.bindings()).thenReturn(List.of(binding));
        when(rejected.unavailable()).thenReturn(List.of());
        when(rejected.call(any(), any(), any())).thenThrow(failure(McpTurnTools.CallFailure.Reason.AUTHORIZATION_REQUIRED));
        new McpTools(rejected, () -> {}, Duration.ofSeconds(30), 10,
                events::add, new ChatToolActivity(ignored -> {}), () -> 8000,
                new JTokkitTokenCountEstimator(EncodingType.O200K_BASE), meters).tools().getFirst().call("{}");
        assertEquals(1, meters.find("memoryos.chat.mcp.call").tag("outcome", "auth_required").timer().count());
    }

    @Test
    void closesItsStepWithTheActionableFailureCategoryOnly() {
        var rejected = mock(McpTurnTools.class);
        when(rejected.bindings()).thenReturn(List.of(binding));
        when(rejected.unavailable()).thenReturn(List.of());
        when(rejected.call(any(), any(), any()))
                .thenThrow(failure(McpTurnTools.CallFailure.Reason.AUTHORIZATION_REQUIRED))
                .thenThrow(failure(McpTurnTools.CallFailure.Reason.UNKNOWN_TOOL));
        var tool = new McpTools(rejected, () -> {}, Duration.ofSeconds(30), 10,
                events::add, new ChatToolActivity(ignored -> {}), () -> 8000,
                new JTokkitTokenCountEstimator(EncodingType.O200K_BASE), meters).tools().getFirst();

        tool.call("{}");
        tool.call("{}");

        // Without the runner's inspector the tool opens and closes its own steps rather than leaving them running.
        assertEquals(List.of(ChatToolEvent.Stage.STARTED, ChatToolEvent.Stage.FAILED, ChatToolEvent.Stage.STARTED, ChatToolEvent.Stage.FAILED),
                events.stream().map(ChatToolEvent::stage).toList());
        assertEquals(ChatToolEvent.Failure.AUTHORIZATION_REQUIRED, events.get(1).failure());
        // A failure the person cannot act on (the model named a tool the server no longer has) carries no category.
        assertEquals(null, events.get(3).failure());
    }

    @Test
    void reportsTheCategoryThroughTheRunnerInspectorStep() {
        var rejected = mock(McpTurnTools.class);
        when(rejected.bindings()).thenReturn(List.of(binding));
        when(rejected.unavailable()).thenReturn(List.of());
        when(rejected.call(any(), any(), any())).thenThrow(failure(McpTurnTools.CallFailure.Reason.TIMEOUT));
        var activity = new ChatToolActivity(events::add);
        var tool = new McpTools(rejected, () -> {}, Duration.ofSeconds(30), 10,
                events::add, activity, () -> 8000,
                new JTokkitTokenCountEstimator(EncodingType.O200K_BASE), meters).tools().getFirst();

        var call = activity.begin("call_1", "mcp_drive_search_files");
        tool.call("{}");
        activity.end(call, false, 5);

        assertEquals(2, events.size());
        assertEquals(ChatToolEvent.Stage.FAILED, events.getLast().stage());
        assertEquals(ChatToolEvent.Failure.TIMEOUT, events.getLast().failure());
    }

    private static McpTurnTools.CallFailure failure(McpTurnTools.CallFailure.Reason reason) {
        return new McpTurnTools.CallFailure(reason);
    }
}
