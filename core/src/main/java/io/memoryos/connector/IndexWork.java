package io.memoryos.connector;

import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.tenant.TenantId;

import java.time.Duration;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record IndexWork(
        SourceOperationId operationId,
        TenantId tenantId,
        UUID connectorId,
        SourceId sourceId,
        SourceItemId itemId,
        UUID claimToken,
        StoredObjectReference object,
        @Nullable Duration initialQueueWait
) {
}
