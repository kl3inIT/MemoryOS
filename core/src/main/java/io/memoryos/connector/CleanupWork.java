package io.memoryos.connector;

import io.memoryos.iam.TenantId;

import java.time.Duration;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record CleanupWork(
        SourceOperationId operationId,
        TenantId tenantId,
        SourceOperationType type,
        SourceId sourceId,
        @Nullable SourceItemId itemId,
        UUID claimToken,
        @Nullable Duration initialQueueWait
) {
}
