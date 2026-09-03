package io.memoryos.connector;

import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.tenant.TenantId;

import java.util.UUID;

public record IndexWork(
        SourceOperationId operationId,
        TenantId tenantId,
        UUID connectorId,
        SourceId sourceId,
        SourceItemId itemId,
        UUID claimToken,
        StoredObjectReference object
) {
}
