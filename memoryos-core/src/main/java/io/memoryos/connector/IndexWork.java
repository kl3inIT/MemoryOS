package io.memoryos.connector;

import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.shared.TenantId;

import java.time.Duration;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * @param attempt how many attempts of the retry budget this claim is, counting itself; a deferred claim is not
 *                counted
 */
public record IndexWork(
        SourceOperationId operationId,
        TenantId tenantId,
        UUID connectorId,
        SourceId sourceId,
        SourceItemId itemId,
        UUID claimToken,
        StoredObjectReference object,
        SourceInputDescriptor input,
        @Nullable Duration initialQueueWait,
        int attempt
) {
}
