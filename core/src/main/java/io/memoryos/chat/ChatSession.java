package io.memoryos.chat;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code reasoningEffort} is the level pinned on this conversation; null lets the model configuration decide. */
public record ChatSession(UUID id, UUID personaId, UUID rootMessageId, String title,
        Instant createdAt, Instant updatedAt, @Nullable UUID projectId,
        io.memoryos.chat.preferences.@Nullable ReasoningEffort reasoningEffort) {
    public ChatSession(UUID id, UUID personaId, UUID rootMessageId, String title,
            Instant createdAt, Instant updatedAt, @Nullable UUID projectId) {
        this(id, personaId, rootMessageId, title, createdAt, updatedAt, projectId, null);
    }
}
