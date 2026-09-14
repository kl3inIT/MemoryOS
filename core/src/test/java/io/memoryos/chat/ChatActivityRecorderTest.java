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
    private final ChatToolEvent.Call search = new ChatToolEvent.Call("call_1", "searchKnowledge");
    private final ChatToolEvent.Call read = new ChatToolEvent.Call("call_2", "read_file");

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
    void boundsStepsReasoningAndSerializedSizeWithoutFailingTheTurn() {
        var recorder = new ChatActivityRecorder();
        var documents = IntStream.range(0, 10).mapToObj(index ->
                new ChatToolEvent.ReadingDocument(UUID.randomUUID(), UUID.randomUUID(), "T".repeat(255), index, index)).toList();
        for (int step = 0; step < 40; step++) {
            var call = new ChatToolEvent.Call("call_" + step, "searchKnowledge");
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
                new ChatToolEvent("call_1", "searchKnowledge", ChatToolEvent.Stage.STARTED, null, null, List.of(), 5L));
        assertThrows(IllegalArgumentException.class, () -> new ChatToolEvent.Call("call_1", "bad name"));
        assertThrows(IllegalArgumentException.class, () -> new ChatReasoningDelta(""));
    }
}
