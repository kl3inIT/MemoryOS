package io.memoryos.connector;

import io.memoryos.document.DocumentId;
import io.memoryos.shared.TenantId;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface ConnectorIndexingPort {

    Optional<IndexWork> claim(TenantId tenantId, SourceOperationId operationId, UUID deliveryId);

    boolean renew(IndexWork work);

    boolean retry(IndexWork work, String errorCode, @Nullable String errorMessage,
            @Nullable String errorDetail, int maxAttempts, Duration backoff);

    Optional<DocumentId> findMappedDocument(IndexWork work);

    boolean complete(IndexWork work, DocumentId documentId);

    void supersede(IndexWork work);

    boolean fail(IndexWork work, String errorCode, @Nullable String errorMessage,
            @Nullable String errorDetail);
}
