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
 */
public record ChatSession(UUID id, UUID personaId, UUID rootMessageId, String title,
        Instant createdAt, Instant updatedAt, @Nullable UUID projectId,
        @Nullable ReasoningEffort reasoningEffort, @Nullable Instant archivedAt,
        @Nullable UUID branchedFromSessionId, @Nullable UUID branchedFromMessageId, boolean temporary) {
    public ChatSession(UUID id, UUID personaId, UUID rootMessageId, String title,
            Instant createdAt, Instant updatedAt, @Nullable UUID projectId) {
        this(id, personaId, rootMessageId, title, createdAt, updatedAt, projectId, null, null, null, null, false);
    }

    public ChatSession(UUID id, UUID personaId, UUID rootMessageId, String title,
            Instant createdAt, Instant updatedAt, @Nullable UUID projectId,
            @Nullable ReasoningEffort reasoningEffort) {
        this(id, personaId, rootMessageId, title, createdAt, updatedAt, projectId, reasoningEffort, null, null,
                null, false);
    }

    public boolean archived() { return archivedAt != null; }
}
