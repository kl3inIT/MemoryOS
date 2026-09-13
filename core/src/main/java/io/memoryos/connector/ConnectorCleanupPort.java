package io.memoryos.connector;

import io.memoryos.iam.TenantId;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public interface ConnectorCleanupPort {

    Optional<CleanupWork> claim(TenantId tenantId, SourceOperationId operationId, UUID deliveryId);
    boolean renew(CleanupWork work);


    boolean retry(CleanupWork work, String errorCode, @Nullable String errorMessage, @Nullable String errorDetail, int maxAttempts, Duration backoff);
    List<CleanupObject> objects(CleanupWork work);

    boolean execute(CleanupWork work);

    boolean fail(CleanupWork work, String errorCode, @Nullable String errorMessage, @Nullable String errorDetail);
}
