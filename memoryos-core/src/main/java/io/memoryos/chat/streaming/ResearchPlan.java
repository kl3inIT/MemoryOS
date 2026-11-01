package io.memoryos.chat.streaming;

import java.util.Objects;
import java.util.UUID;

/** A chunk of the Deep research plan. */
public record ResearchPlan(UUID assistantMessageId, long sequence, String text) implements ChatStreamEvent {
    public static final String TYPE = "research_plan";

    public ResearchPlan {
        Objects.requireNonNull(text, "text");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
