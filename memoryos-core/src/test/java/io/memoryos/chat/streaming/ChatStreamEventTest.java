package io.memoryos.chat.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.memoryos.chat.ChatCodeEvent;
import io.memoryos.chat.ChatImageEvent;
import io.memoryos.chat.ChatMessage.Status;
import io.memoryos.chat.ChatResearchEvent;
import io.memoryos.chat.ChatToolEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChatStreamEventTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID reply = UUID.randomUUID();

    @Test
    void everyEventKeepsItsWireNameAndItsComponentsThroughRedis() {
        var events = List.<ChatStreamEvent>of(
                new TextDelta(reply, 1, "Answer"),
                new Reasoning(reply, 2, "Thinking", "call_agent"),
                new Reasoning(reply, 3, "Thinking", null),
                new ResearchPlan(reply, 4, "1. Revenue"),
                new TopLevelBranching(reply, 5, 2),
                new ResearchAgentStart(reply, 6, "call_agent", 1, "Revenue in 2025"),
                new IntermediateReport(reply, 7, "call_agent", "Revenue grew [1]."),
                new IntermediateReportCitations(reply, 8, "call_agent", List.of(new ChatResearchEvent.Citation(1, 4))),
                new ToolProgress(reply, 9, ChatToolEvent.finished(new ChatToolEvent.Call("call_s", "search_knowledge"),
                        true, 12L, ChatToolEvent.Failure.TIMEOUT).nested("call_agent")),
                new ImageProgress(reply, 10, new ChatImageEvent("call_i", ChatImageEvent.Stage.COMPLETED,
                        UUID.randomUUID(), "image/png", "A cat")),
                new CodeRun(reply, 11, ChatCodeEvent.running("call_p", "print(1)")),
                new Outcome(reply, 12, Status.FAILED, "CHAT_INTERRUPTED", true));

        var names = new ArrayList<String>();
        for (var event : events) {
            String json = JSON.writerFor(ChatStreamEvent.class).writeValueAsString(event);
            var tree = JSON.readTree(json);
            names.add(tree.path("type").asString());
            assertEquals(event.type(), tree.path("type").asString());
            assertEquals(false, tree.has("id"), "the event ID is derived, not stored");
            assertEquals(event, JSON.readerFor(ChatStreamEvent.class).<ChatStreamEvent>readValue(json));
        }
        assertEquals(List.of("text", "reasoning", "reasoning", "research_plan", "top_level_branching",
                "research_agent_start", "intermediate_report", "intermediate_report_citations", "tool", "image", "code",
                "outcome"), names);
        assertEquals(reply + ":12", events.getLast().id());
    }

    @Test
    void anEventOfANewerVersionDecodesToUnknownInsteadOfFailingTheReplay() {
        ChatStreamEvent event = JSON.readerFor(ChatStreamEvent.class).readValue(
                "{\"type\":\"video\",\"assistantMessageId\":\"" + reply + "\",\"sequence\":7,\"frames\":[1,2]}");

        assertEquals(new UnknownEvent(reply, 7), assertInstanceOf(UnknownEvent.class, event));
        assertEquals("unknown", event.type());
    }

    @Test
    void aKnownEventIgnoresComponentsANewerVersionAdded() {
        ChatStreamEvent event = JSON.readerFor(ChatStreamEvent.class).readValue(
                "{\"type\":\"text\",\"assistantMessageId\":\"" + reply + "\",\"sequence\":1,\"text\":\"a\",\"tone\":\"warm\"}");

        assertEquals(new TextDelta(reply, 1, "a"), event);
    }
}
