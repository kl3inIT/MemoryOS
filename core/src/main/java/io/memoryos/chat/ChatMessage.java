package io.memoryos.chat;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ChatMessage(UUID id, UUID sessionId, @Nullable UUID parentMessageId,
        @Nullable UUID latestChildMessageId, Role role, @Nullable String content, Status status,
        Instant createdAt, @Nullable Instant finishedAt, List<ChatSource> sources, List<ChatFileDescriptor> files) {
    public ChatMessage { sources = List.copyOf(sources); files = List.copyOf(files); }
    public ChatMessage(UUID id, UUID sessionId, @Nullable UUID parentMessageId, @Nullable UUID latestChildMessageId,
                       Role role, @Nullable String content, Status status, Instant createdAt, @Nullable Instant finishedAt, List<ChatSource> sources) {
        this(id, sessionId, parentMessageId, latestChildMessageId, role, content, status, createdAt, finishedAt, sources, List.of());
    }
    public ChatMessage(UUID id, UUID sessionId, @Nullable UUID parentMessageId, @Nullable UUID latestChildMessageId,
                       Role role, @Nullable String content, Status status, Instant createdAt, @Nullable Instant finishedAt) {
        this(id, sessionId, parentMessageId, latestChildMessageId, role, content, status, createdAt, finishedAt, List.of());
    }
    public enum Role { ROOT, USER, ASSISTANT }
    public enum Status { COMPLETED, RUNNING, CANCELED, FAILED }
}
