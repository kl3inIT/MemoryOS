package io.memoryos.connector;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record SourceIndexAttemptView(
        SourceOperationId id,
        @Nullable String filename,
        SourceOperationStatus status,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant completedAt,
        @Nullable String errorCode
) {}
