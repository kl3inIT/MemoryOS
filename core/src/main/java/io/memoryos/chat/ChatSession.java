package io.memoryos.chat;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record ChatSession(UUID id, UUID personaId, UUID rootMessageId, String title,
        Instant createdAt, Instant updatedAt, @Nullable UUID projectId) {}
