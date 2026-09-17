package io.memoryos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.retrieval.SearchFilters;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatResearchRecorderTest {
    private final ChatToolEvent.Call revenue = new ChatToolEvent.Call("call_revenue", "research_agent");
    private final ChatToolEvent.Call costs = new ChatToolEvent.Call("call_costs", "research_agent");
    private final ChatToolEvent.Call policy = new ChatToolEvent.Call("call_policy", "research_agent");
    private final ChatToolEvent.Call search = new ChatToolEvent.Call("call_search", "search_knowledge");

    @Test
    void recordsAgentsByCycleAndTabWithTheirStepsReportsAndCitations() {
        var recorder = new ChatResearchRecorder();
        recorder.accept(ChatResearchEvent.plan("1. Revenue"));
        recorder.accept(ChatResearchEvent.branching(2));
        recorder.accept(new ChatToolEvent(revenue, ChatToolEvent.Stage.STARTED).tab(0));
        recorder.accept(new ChatToolEvent(costs, ChatToolEvent.Stage.STARTED).tab(1));
        recorder.accept(ChatResearchEvent.agent("call_revenue", 0, "Revenue in 2025"));
        recorder.accept(new ChatToolEvent(search, ChatToolEvent.Stage.STARTED).nested("call_revenue"));
        recorder.accept(new ChatToolEvent(search, new ChatToolEvent.QueryPlan(List.of("revenue 2025"), SearchFilters.NONE)).nested("call_revenue"));
        recorder.accept(ChatToolEvent.finished(search, false, 12L).nested("call_revenue"));
        recorder.accept(new ChatReasoningDelta("Next: margins", "call_revenue"));
        recorder.accept(ChatResearchEvent.report("call_revenue", "Revenue grew "));
        recorder.accept(ChatResearchEvent.report("call_revenue", "[1]."));
        recorder.accept(ChatResearchEvent.citations("call_revenue", List.of(new ChatResearchEvent.Citation(1, 4))));
        recorder.accept(ChatToolEvent.finished(revenue, false, 900L).tab(0));
        recorder.accept(ChatToolEvent.finished(costs, true, 30L).tab(1));
        // Top-level steps and unknown agents belong elsewhere.
        recorder.accept(new ChatToolEvent(search, ChatToolEvent.Stage.STARTED));
        recorder.accept(ChatResearchEvent.report("call_unknown", "ignored"));
        recorder.accept(new ChatToolEvent(policy, ChatToolEvent.Stage.STARTED).tab(0));

        var agents = recorder.seal();

        assertEquals(List.of("call_revenue", "call_costs", "call_policy"), agents.stream().map(ChatResearch.Agent::toolCallId).toList());
        assertEquals(List.of(0, 0, 1), agents.stream().map(ChatResearch.Agent::cycle).toList());
        assertEquals(List.of(0, 1, 0), agents.stream().map(ChatResearch.Agent::tabIndex).toList());
        var first = agents.getFirst();
        assertEquals("Revenue in 2025", first.task());
        assertEquals(ChatActivity.StepStatus.COMPLETED, first.status());
        assertEquals(900L, first.durationMs());
        assertEquals("Revenue grew [1].", first.report());
        assertEquals(List.of(new ChatResearchEvent.Citation(1, 4)), first.citations());
        assertEquals(List.of("revenue 2025"), first.activity().steps().getFirst().queries());
        assertEquals("Next: margins", first.activity().reasoning().getFirst().text());
        assertEquals(ChatActivity.StepStatus.FAILED, agents.get(1).status());
        assertNull(agents.get(1).report());
        assertEquals(ChatActivity.StepStatus.FAILED, agents.get(2).status(), "An agent running at the outcome was interrupted");
    }

    @Test
    void boundsReportTextAndTheSerializedTreeWithoutFailingTheTurn() {
        var recorder = new ChatResearchRecorder();
        for (int cycle = 0; cycle < 12; cycle++) {
            for (int tab = 0; tab < 3; tab++) {
                String id = "call_" + cycle + "_" + tab;
                recorder.accept(new ChatToolEvent(new ChatToolEvent.Call(id, "research_agent"), ChatToolEvent.Stage.STARTED).tab(tab));
                recorder.accept(ChatResearchEvent.report(id, "r".repeat(ChatActivity.MAX_REASONING)));
                for (int i = 0; i < 7; i++) recorder.accept(ChatResearchEvent.report(id, "r".repeat(ChatActivity.MAX_REASONING)));
            }
        }
        var agents = recorder.seal();
        assertEquals(36, agents.size());
        assertEquals(ChatResearch.MAX_REPORT + ChatActivity.TRUNCATED.length(), agents.getFirst().report().length());
        assertTrue(ChatResearchRecorder.size(agents) <= ChatResearchRecorder.BYTE_BUDGET);
        assertNull(agents.getLast().report(), "The latest agents lose their reports first");
    }
}
