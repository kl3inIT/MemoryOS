package io.memoryos.chat.streaming;

import java.util.UUID;

/** The reply waits for a turn ({@code waiting}) or has just been given one; sent only for a reply that waited. */
public record TurnWaiting(UUID assistantMessageId, long sequence, boolean waiting) implements ChatStreamEvent {
    public static final String TYPE = "waiting";

    @Override
    public String type() {
        return TYPE;
    }
}
