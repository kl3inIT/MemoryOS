package io.memoryos.chat.streaming;

import java.util.Objects;
import java.util.UUID;

/** A research agent started on its task, at its tab among the agents of the cycle. */
public record ResearchAgentStart(UUID assistantMessageId, long sequence,
        String toolCallId, int tabIndex, String task) implements ChatStreamEvent {
    public static final String TYPE = "research_agent_start";

    public ResearchAgentStart {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(task, "task");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
