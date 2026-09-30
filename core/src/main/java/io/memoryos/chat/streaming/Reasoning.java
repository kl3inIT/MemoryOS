package io.memoryos.chat.streaming;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A chunk of the model's reasoning; {@code parentToolCallId} is the research agent call it belongs to, if any. */
public record Reasoning(UUID assistantMessageId, long sequence,
        String text, @Nullable String parentToolCallId) implements ChatStreamEvent {
    public static final String TYPE = "reasoning";

    public Reasoning {
        Objects.requireNonNull(text, "text");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
