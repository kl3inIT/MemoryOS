package io.memoryos.chat.streaming;

import java.util.Objects;
import java.util.UUID;

/** A chunk of the answer text. */
public record TextDelta(UUID assistantMessageId, long sequence, String text) implements ChatStreamEvent {
    public static final String TYPE = "text";

    public TextDelta {
        Objects.requireNonNull(text, "text");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
