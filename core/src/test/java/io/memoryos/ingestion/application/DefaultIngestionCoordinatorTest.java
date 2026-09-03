package io.memoryos.ingestion.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.connector.CleanupWork;
import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.StoredObjectRegistry;
import io.memoryos.tenant.TenantId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class DefaultIngestionCoordinatorTest {

    @Test
    void renewsAndCancelsTheCleanupLeaseWhileProcessing() {
        var indexing = mock(ConnectorIndexingPort.class);
        var cleanup = mock(ConnectorCleanupPort.class);
        var documents = mock(DocumentCommandPort.class);
        var extractor = mock(SourceContentExtractor.class);
        var storage = mock(ObjectStorage.class);
        var storedObjects = mock(StoredObjectRegistry.class);
        var transactions = mock(TransactionTemplate.class);
        var scheduler = mock(ScheduledExecutorService.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<Object> renewal = mock(ScheduledFuture.class);
        var tenantId = new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000052"));
        var operationId = new SourceOperationId(UUID.fromString("20000000-0000-0000-0000-000000000052"));
        var deliveryId = UUID.fromString("30000000-0000-0000-0000-000000000052");
        var work = new CleanupWork(
                operationId,
                tenantId,
                SourceOperationType.DELETE_SOURCE,
                new SourceId(UUID.fromString("40000000-0000-0000-0000-000000000052")),
                null,
                UUID.fromString("50000000-0000-0000-0000-000000000052")
        );
        when(cleanup.claim(tenantId, operationId, deliveryId)).thenReturn(Optional.of(work));
        when(cleanup.objects(work)).thenReturn(List.of());
        when(cleanup.execute(work)).thenReturn(true);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), eq(30L), eq(30L), eq(TimeUnit.SECONDS)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return renewal;
                });
        var coordinator = new DefaultIngestionCoordinator(
                indexing,
                cleanup,
                documents,
                extractor,
                storage,
                storedObjects,
                transactions,
                scheduler
        );

        coordinator.process(new OperationDelivery(tenantId, OperationWorkload.CLEANUP, operationId, deliveryId));

        verify(cleanup).renew(work);
        verify(renewal).cancel(false);
    }
}
