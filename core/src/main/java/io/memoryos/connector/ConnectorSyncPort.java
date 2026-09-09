package io.memoryos.connector;

import io.memoryos.iam.TenantId;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface ConnectorSyncPort {
    Optional<Work> claim(TenantId tenant, SourceOperationId operation, UUID deliveryId);
    boolean renew(Work work);
    Result execute(Work work);
    int enqueueDue(int limit);

    record Work(TenantId tenantId, SourceId sourceId, SourceOperationId operationId, UUID claimToken,
                long scopeRevision, long credentialRevision, long generation,
                @Nullable Duration initialQueueWait) {}
    enum Result { CONTINUED, COMPLETED, FAILED, SUPERSEDED }
}
