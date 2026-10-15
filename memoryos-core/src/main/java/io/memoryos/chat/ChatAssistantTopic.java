package io.memoryos.chat;

import java.time.Instant;
import java.util.UUID;

/**
 * The newest topic a person has with an assistant. {@code lastMessageAt} is when its last question or finished answer
 * was written, or when the topic last changed before anyone wrote; {@code preview} is that message as plain text of
 * at most {@link #PREVIEW_LENGTH} characters, empty before anyone wrote.
 */
public record ChatAssistantTopic(UUID sessionId, String title, Instant lastMessageAt, String preview,
                                 boolean replyRunning) {
    public static final int PREVIEW_LENGTH = 120;
}
