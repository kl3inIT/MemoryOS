package io.memoryos.connector;

import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Verifying an accepted scope request against its provider. Every connector that accepts a selection and
 * activates it asynchronously shares this shape, so one worker processor serves all of them.
 */
public interface SourceSelectionProcessor {
    Optional<Work> claim(TenantId tenant, SourceOperationId operation, UUID deliveryId);

    boolean renew(Work work);

    Result execute(Work work);

    record Work(TenantId tenantId, SourceId sourceId, SourceOperationId operationId, UUID claimToken,
            @Nullable Duration initialQueueWait) {}

    enum Result { CONTINUED, COMPLETED, FAILED, SUPERSEDED }
}
