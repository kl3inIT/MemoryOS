package io.memoryos.chat;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code reasoningEffort} is the level pinned on this conversation; null lets the model configuration decide.
 * {@code archivedAt} is set while the conversation is out of the sidebar but kept (MEM-153), and
 * {@code branchedFrom*} name the conversation and message this one was branched from.
 */
public record ChatSession(UUID id, UUID personaId, UUID rootMessageId, String title,
        Instant createdAt, Instant updatedAt, @Nullable UUID projectId,
        io.memoryos.chat.preferences.@Nullable ReasoningEffort reasoningEffort, @Nullable Instant archivedAt,
        @Nullable UUID branchedFromSessionId, @Nullable UUID branchedFromMessageId) {
    public ChatSession(UUID id, UUID personaId, UUID rootMessageId, String title,
            Instant createdAt, Instant updatedAt, @Nullable UUID projectId) {
        this(id, personaId, rootMessageId, title, createdAt, updatedAt, projectId, null, null, null, null);
    }

    public ChatSession(UUID id, UUID personaId, UUID rootMessageId, String title,
            Instant createdAt, Instant updatedAt, @Nullable UUID projectId,
            io.memoryos.chat.preferences.@Nullable ReasoningEffort reasoningEffort) {
        this(id, personaId, rootMessageId, title, createdAt, updatedAt, projectId, reasoningEffort, null, null, null);
    }

    public boolean archived() { return archivedAt != null; }
}
