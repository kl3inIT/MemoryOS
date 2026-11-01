package io.memoryos.chat.streaming;

import java.util.UUID;

/** An event this version does not know, written by a newer one during a rolling deploy; readers skip it. */
public record UnknownEvent(UUID assistantMessageId, long sequence) implements ChatStreamEvent {
    public static final String TYPE = "unknown";

    @Override
    public String type() {
        return TYPE;
    }
}
