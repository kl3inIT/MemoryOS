package io.memoryos.chat;

import io.memoryos.ai.ReasoningEffort;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code reasoningEffort} is the level pinned on this conversation; null lets the model configuration decide.
 * {@code archivedAt} is set while the conversation is out of the sidebar but kept (MEM-153),
 * {@code branchedFrom*} name the conversation and message this one was branched from, and {@code temporary}
 * marks a conversation that leaves no history: it is listed nowhere and deletes itself with its uploads.
 * {@code replyRunning} is true while an answer is being written in it, as read with the conversation.
 * {@code channelPublic} marks a conversation started through a public bot (MEM-298): it reads its assistant's
 * attachments only, and is continued only while the bot stays public.
 */
public record ChatSession(UUID id, UUID personaId, UUID rootMessageId, String title,
        Instant createdAt, Instant updatedAt, @Nullable UUID projectId,
        @Nullable ReasoningEffort reasoningEffort, @Nullable Instant archivedAt,
        @Nullable UUID branchedFromSessionId, @Nullable UUID branchedFromMessageId, boolean temporary,
        boolean replyRunning, boolean channelPublic) {
    public boolean archived() { return archivedAt != null; }
}
