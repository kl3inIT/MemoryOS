package io.memoryos.chat;

import java.time.Instant;
import java.util.UUID;

public record ChatSession(UUID id, UUID personaId, UUID rootMessageId, String title,
        Instant createdAt, Instant updatedAt) {}
