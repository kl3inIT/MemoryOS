package io.memoryos.connector;

import io.memoryos.tenant.TenantId;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConnectorCleanupPort {

    Optional<CleanupWork> claim(TenantId tenantId, SourceOperationId operationId, UUID deliveryId);

    boolean retry(CleanupWork work, String errorCode, int maxAttempts, Duration backoff);
    List<CleanupObject> objects(CleanupWork work);

    boolean execute(CleanupWork work);

    boolean fail(CleanupWork work, String errorCode);
}
