package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.retrieval.SearchFilters;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChatActivityRecorderTest {
    private final ChatToolEvent.Call search = new ChatToolEvent.Call("call_1", "search_knowledge");
    private final ChatToolEvent.Call read = new ChatToolEvent.Call("call_2", "read_file");

    @Test
    void keepsTheFailureCategoryOfAFailedStepAndRejectsItOnAnyOther() {
        var recorder = new ChatActivityRecorder();
        var mcp = new ChatToolEvent.Call("call_mcp", "mcp_drive_search_files");
        recorder.accept(new ChatToolEvent(mcp, ChatToolEvent.Stage.STARTED), 0);
        recorder.accept(ChatToolEvent.finished(mcp, true, 7L, ChatToolEvent.Failure.AUTHORIZATION_REQUIRED), 0);

        var step = recorder.seal().steps().getFirst();

        assertEquals(ChatActivity.StepStatus.FAILED, step.status());
        assertEquals(ChatToolEvent.Failure.AUTHORIZATION_REQUIRED, step.failure());
        assertEquals(null, ChatToolEvent.finished(mcp, false, 7L, ChatToolEvent.Failure.TIMEOUT).failure());
        assertThrows(IllegalArgumentException.class, () -> new ChatToolEvent(mcp.id(), mcp.name(), ChatToolEvent.Stage.COMPLETED,
                null, null, List.of(), 7L, null, null, ChatToolEvent.Failure.TIMEOUT));
    }

    @Test
    void recordsOrderedStepsSummariesCitationsAndReasoningSegments() {
        var recorder = new ChatActivityRecorder();
        var source = new ChatSource(1, UUID.randomUUID(), UUID.randomUUID(), "HR", 2, 2, List.of(new ChatSource.Provenance(2, "[]")));
        recorder.accept(new ChatReasoningDelta("Looking for "), 0);
        recorder.accept(new ChatReasoningDelta("the policy."), 0);
        recorder.accept(new ChatToolEvent(search, ChatToolEvent.Stage.STARTED), 0);
        recorder.accept(new ChatToolEvent(search, new ChatToolEvent.QueryPlan(List.of("leave policy", "leave policy"), SearchFilters.NONE)), 0);
        recorder.accept(new ChatToolEvent(search, source), 0);
        recorder.accept(new ChatToolEvent(ChatEvidence.FILE_CONTEXT, source), 0);
        recorder.accept(ChatToolEvent.finished(search, false, 42L), 0);
        recorder.accept(new ChatReasoningDelta("Reading the file."), 12);
        recorder.accept(new ChatToolEvent(read, ChatToolEvent.Stage.STARTED), 12);

        var activity = recorder.seal();

        assertEquals(2, activity.steps().size());
        var first = activity.steps().getFirst();
        assertEquals(1, first.position());
        assertEquals(ChatActivity.StepStatus.COMPLETED, first.status());
        assertEquals(42L, first.durationMs());
        assertEquals(List.of("leave policy"), first.queries());
        assertEquals(List.of(1), first.citations());
        var interrupted = activity.steps().getLast();
        assertEquals("read_file", interrupted.toolName());
        assertEquals(ChatActivity.StepStatus.FAILED, interrupted.status());
        assertEquals(3, interrupted.position());
        assertEquals(12, interrupted.textOffset());
        assertNotNull(interrupted.durationMs());
        assertEquals(List.of(new ChatActivity.ReasoningSegment(0, 0, "Looking for the policy."),
                new ChatActivity.ReasoningSegment(2, 12, "Reading the file.")), activity.reasoning());
    }

    @Test
    void researchAgentStepsReasoningAndProgressStayOutOfTopLevelActivity() {
        var recorder = new ChatActivityRecorder();
        var agent = new ChatToolEvent.Call("call_agent", "research_agent");
        var nestedSource = new ChatSource(1, UUID.randomUUID(), UUID.randomUUID(), "HR", 2, 2, List.of(new ChatSource.Provenance(2, "[]")));
        recorder.accept(ChatResearchEvent.plan("1. Revenue"), 0);
        recorder.accept(new ChatToolEvent(agent, ChatToolEvent.Stage.STARTED).tab(0), 0);
        recorder.accept(ChatResearchEvent.agent("call_agent", 0, "Revenue in 2025"), 0);
        recorder.accept(new ChatToolEvent(search, ChatToolEvent.Stage.STARTED).nested("call_agent"), 0);
        recorder.accept(new ChatToolEvent(search, nestedSource).nested("call_agent"), 0);
        recorder.accept(new ChatReasoningDelta("Agent thinking", "call_agent"), 0);
        recorder.accept(ChatResearchEvent.report("call_agent", "Revenue grew [1]."), 0);
        recorder.accept(ChatToolEvent.finished(agent, false, 5L), 0);

        var activity = recorder.seal();

        assertEquals(List.of("call_agent"), activity.steps().stream().map(ChatActivity.ActivityStep::toolCallId).toList());
        assertEquals(List.of(), activity.steps().getFirst().citations());
        assertEquals(List.of(), activity.reasoning());
    }

    @Test
    void researchEventsValidatePlacementAndBounds() {
        var call = new ChatToolEvent.Call("call_1", "research_agent");
        assertThrows(IllegalArgumentException.class, () -> new ChatToolEvent(call, ChatToolEvent.Stage.STARTED).tab(3));
        assertThrows(IllegalArgumentException.class, () -> new ChatToolEvent(call, ChatToolEvent.Stage.STARTED).tab(0).nested("call_0").tab(1).nested(""));
        assertThrows(IllegalArgumentException.class, () -> ChatResearchEvent.branching(1));
        assertThrows(IllegalArgumentException.class, () -> ChatResearchEvent.branching(4));
        assertThrows(IllegalArgumentException.class, () -> ChatResearchEvent.agent("call_1", 0, "t".repeat(ChatResearchEvent.MAX_TASK + 1)));
        assertThrows(IllegalArgumentException.class, () -> ChatResearchEvent.report("call_1", ""));
        assertThrows(IllegalArgumentException.class, () -> new ChatResearchEvent.Citation(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ChatReasoningDelta("text", " "));
        assertEquals(List.of(new ChatResearchEvent.Citation(1, 4)),
                ChatResearchEvent.citations("call_1", List.of(new ChatResearchEvent.Citation(1, 4))).citations());
    }

    @Test
    void boundsStepsReasoningAndSerializedSizeWithoutFailingTheTurn() {
        var recorder = new ChatActivityRecorder();
        var documents = IntStream.range(0, 10).mapToObj(index ->
                new ChatToolEvent.ReadingDocument(UUID.randomUUID(), UUID.randomUUID(), "T".repeat(255), index, index)).toList();
        for (int step = 0; step < 40; step++) {
            var call = new ChatToolEvent.Call("call_" + step, "search_knowledge");
            recorder.accept(new ChatToolEvent(call, ChatToolEvent.Stage.STARTED), 0);
            recorder.accept(new ChatToolEvent(call, new ChatToolEvent.QueryPlan(
                    IntStream.range(0, 8).mapToObj(query -> query + "q".repeat(1990)).toList(), SearchFilters.NONE)), 0);
            recorder.accept(ChatToolEvent.reading(call, documents), 0);
            recorder.accept(ChatToolEvent.finished(call, false, 5L), 0);
        }
        recorder.accept(new ChatReasoningDelta("r".repeat(ChatActivity.MAX_REASONING)), 0);
        recorder.accept(new ChatReasoningDelta("more"), 0);

        var activity = recorder.seal();

        assertEquals(ChatActivity.MAX_ITEMS, activity.steps().size());
        assertTrue(ChatActivityRecorder.size(activity) <= ChatActivityRecorder.BYTE_BUDGET);
        assertEquals(8, activity.steps().getFirst().queries().size());
        assertEquals(500, activity.steps().getFirst().queries().getFirst().length());
        assertTrue(activity.steps().getLast().queries().isEmpty());
        assertTrue(activity.steps().getLast().documents().isEmpty());
        assertTrue(activity.reasoning().getFirst().text().endsWith(ChatActivity.TRUNCATED));
    }

    @Test
    void terminalDurationsBelongOnlyToTerminalStages() {
        assertThrows(IllegalArgumentException.class, () ->
                new ChatToolEvent("call_1", "search_knowledge", ChatToolEvent.Stage.STARTED, null, null, List.of(), 5L));
        assertThrows(IllegalArgumentException.class, () -> new ChatToolEvent.Call("call_1", "bad name"));
        assertThrows(IllegalArgumentException.class, () -> new ChatReasoningDelta(""));
    }
}
