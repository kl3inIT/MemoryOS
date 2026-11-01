package io.memoryos.connector;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A retained run or file error. {@code resolvedAt} is set when a later run acquired the same item
 * again, as Onyx resolves an {@code IndexAttemptError}.
 */
public record SourceRunError(
        UUID id, UUID runId, @Nullable UUID operationId, @Nullable UUID itemId,
        @Nullable String fileId, @Nullable String fileName, SourceRunErrorStage stage,
        String code, Instant occurredAt, @Nullable Instant resolvedAt,
        @Nullable String errorMessage, @Nullable String errorDetail,
        @Nullable SourceItemStatus currentItemStatus, @Nullable String currentItemErrorCode,
        @Nullable Instant currentItemLastIndexedAt
) {}
