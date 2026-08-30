package io.memoryos.connector;

import io.memoryos.tenant.TenantId;


import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record CleanupWork(
        SourceOperationId operationId,
        TenantId tenantId,
        SourceOperationType type,
        UUID sourceId,
        @Nullable SourceItemId itemId,
        UUID claimToken
) {
}
