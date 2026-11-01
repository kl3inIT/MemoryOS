package io.memoryos.connector;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

public record SourceOperationView(
        SourceOperationId id,
        SourceOperationType type,
        SourceOperationStatus status,
        Instant createdAt,
        @Nullable Instant completedAt,
        @Nullable String errorCode
) {
}
