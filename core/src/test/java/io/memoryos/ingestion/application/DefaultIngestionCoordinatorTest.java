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
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    @Test
    void handledCleanupFailureReportsFailedAndKeepsDatabaseRetry() {
        var cleanup = mock(ConnectorCleanupPort.class);
        var scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(renewal).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), eq(30L), eq(30L), eq(TimeUnit.SECONDS));
        var delivery = new OperationDelivery(new TenantId(UUID.randomUUID()), OperationWorkload.CLEANUP,
                new SourceOperationId(UUID.randomUUID()), UUID.randomUUID());
        var work = new CleanupWork(delivery.operationId(), delivery.tenantId(), SourceOperationType.DELETE_SOURCE,
                new SourceId(UUID.randomUUID()), null, UUID.randomUUID(), java.time.Duration.ofSeconds(2));
        when(cleanup.claim(delivery.tenantId(), delivery.operationId(), delivery.deliveryId())).thenReturn(Optional.of(work));
        when(cleanup.objects(work)).thenThrow(new IllegalStateException("test failure"));
        var coordinator = new DefaultIngestionCoordinator(mock(ConnectorIndexingPort.class), cleanup,
                mock(DocumentCommandPort.class), mock(SourceContentExtractor.class), mock(ObjectStorage.class),
                mock(StoredObjectRegistry.class), mock(TransactionTemplate.class), scheduler,
                mock(io.memoryos.document.ExtractionArtifactPort.class), registry);

        org.assertj.core.api.Assertions.assertThat(coordinator.process(delivery))
                .isEqualTo(io.memoryos.ingestion.IngestionCoordinator.Outcome.FAILED);
        assertOutcome("CLEANUP", "FAILED");
        assertWait("CLEANUP", 1);
        verify(cleanup).retry(work, "SOURCE_CLEANUP_INTERNAL", 3, java.time.Duration.ofSeconds(5));
        verify(renewal).cancel(false);
    }

    @Test
    void typedExtractionFailureReportsFailedAndPersistsFailure() throws Exception {
        var indexing = mock(ConnectorIndexingPort.class);
        var scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(renewal).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), eq(30L), eq(30L), eq(TimeUnit.SECONDS));
        var delivery = new OperationDelivery(new TenantId(UUID.randomUUID()), OperationWorkload.INGESTION,
                new SourceOperationId(UUID.randomUUID()), UUID.randomUUID());
        var reference = mock(io.memoryos.objectstorage.StoredObjectReference.class);
        var metadata = mock(io.memoryos.objectstorage.ObjectMetadata.class);
        when(reference.metadata()).thenReturn(metadata);
        var work = new io.memoryos.connector.IndexWork(delivery.operationId(), delivery.tenantId(),
                UUID.randomUUID(), new SourceId(UUID.randomUUID()), null, UUID.randomUUID(), reference, java.time.Duration.ofSeconds(2));
        when(indexing.claim(delivery.tenantId(), delivery.operationId(), delivery.deliveryId())).thenReturn(Optional.of(work));
        var storage = mock(ObjectStorage.class);
        var content = mock(io.memoryos.objectstorage.ObjectContent.class);
        when(storage.open(reference.key())).thenReturn(content);
        when(content.metadata()).thenReturn(metadata);
        var extractor = mock(SourceContentExtractor.class);
        when(extractor.extract(content.inputStream(), metadata.sizeBytes(), reference.filename()))
                .thenThrow(new io.memoryos.ingestion.ExtractionException(
                        io.memoryos.ingestion.ExtractionFailure.MALFORMED, "test failure"));
        var coordinator = new DefaultIngestionCoordinator(indexing, mock(ConnectorCleanupPort.class),
                mock(DocumentCommandPort.class), extractor, storage, mock(StoredObjectRegistry.class),
                mock(TransactionTemplate.class), scheduler,
                mock(io.memoryos.document.ExtractionArtifactPort.class), registry);

        org.assertj.core.api.Assertions.assertThat(coordinator.process(delivery))
                .isEqualTo(io.memoryos.ingestion.IngestionCoordinator.Outcome.FAILED);
        assertOutcome("INGESTION", "FAILED");
        assertWait("INGESTION", 1);
        verify(indexing).fail(work, "SOURCE_EXTRACTION_MALFORMED");
        verify(content).close();
        verify(renewal).cancel(false);
    }

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
                UUID.fromString("50000000-0000-0000-0000-000000000052"), java.time.Duration.ofSeconds(2)
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
                scheduler,
                mock(io.memoryos.document.ExtractionArtifactPort.class), registry
        );

        coordinator.process(new OperationDelivery(tenantId, OperationWorkload.CLEANUP, operationId, deliveryId));

        assertOutcome("CLEANUP", "COMPLETED");
        assertWait("CLEANUP", 1);
        verify(cleanup).renew(work);
        verify(renewal).cancel(false);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(OperationWorkload.class)
    void missingClaimCountsSkippedWithoutQueueWait(OperationWorkload workload) {
        var coordinator = new DefaultIngestionCoordinator(mock(ConnectorIndexingPort.class), mock(ConnectorCleanupPort.class),
                mock(DocumentCommandPort.class), mock(SourceContentExtractor.class), mock(ObjectStorage.class),
                mock(StoredObjectRegistry.class), mock(TransactionTemplate.class), mock(ScheduledExecutorService.class),
                mock(io.memoryos.document.ExtractionArtifactPort.class), registry);
        coordinator.process(new OperationDelivery(new TenantId(UUID.randomUUID()), workload,
                new SourceOperationId(UUID.randomUUID()), UUID.randomUUID()));
        assertOutcome(workload.name(), "SKIPPED");
        assertWait(workload.name(), 0);
    }

    @Test
    void claimFailureCountsOnlyUnhandledAndPropagatesOriginalException() {
        var indexing = mock(ConnectorIndexingPort.class);
        var failure = new IllegalStateException("private database failure");
        when(indexing.claim(any(), any(), any())).thenThrow(failure);
        var coordinator = new DefaultIngestionCoordinator(indexing, mock(ConnectorCleanupPort.class),
                mock(DocumentCommandPort.class), mock(SourceContentExtractor.class), mock(ObjectStorage.class),
                mock(StoredObjectRegistry.class), mock(TransactionTemplate.class), mock(ScheduledExecutorService.class),
                mock(io.memoryos.document.ExtractionArtifactPort.class), registry);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> coordinator.process(new OperationDelivery(
                new TenantId(UUID.randomUUID()), OperationWorkload.INGESTION,
                new SourceOperationId(UUID.randomUUID()), UUID.randomUUID()))).isSameAs(failure);
        assertOutcome("INGESTION", "UNHANDLED");
        assertWait("INGESTION", 0);
    }

    @Test
    void metricRecordingFailureDoesNotChangeBusinessOutcome() {
        var failingRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry() {
            @Override
            protected io.micrometer.core.instrument.Counter newCounter(io.micrometer.core.instrument.Meter.Id id) {
                var counter = mock(io.micrometer.core.instrument.Counter.class);
                org.mockito.Mockito.doThrow(new IllegalStateException("test metric failure")).when(counter).increment();
                return counter;
            }
        };
        var coordinator = new DefaultIngestionCoordinator(mock(ConnectorIndexingPort.class), mock(ConnectorCleanupPort.class),
                mock(DocumentCommandPort.class), mock(SourceContentExtractor.class), mock(ObjectStorage.class),
                mock(StoredObjectRegistry.class), mock(TransactionTemplate.class), mock(ScheduledExecutorService.class),
                mock(io.memoryos.document.ExtractionArtifactPort.class), failingRegistry);
        org.assertj.core.api.Assertions.assertThat(coordinator.process(new OperationDelivery(new TenantId(UUID.randomUUID()),
                OperationWorkload.INGESTION, new SourceOperationId(UUID.randomUUID()), UUID.randomUUID())))
                .isEqualTo(io.memoryos.ingestion.IngestionCoordinator.Outcome.SKIPPED);
    }

    @Test
    void retryClaimOmitsWaitAndClockRollbackClampsToZero() {
        var metrics = new IngestionMetrics(registry);
        metrics.firstClaim(OperationWorkload.INGESTION, null);
        assertWait("INGESTION", 0);
        metrics.firstClaim(OperationWorkload.INGESTION, java.time.Duration.ofSeconds(-1));
        var timer = registry.get("memoryos.operation.initial.queue.wait").tag("workload", "INGESTION").timer();
        org.assertj.core.api.Assertions.assertThat(timer.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(timer.totalTime(TimeUnit.SECONDS)).isZero();
    }

    private void assertOutcome(String workload, String expected) {
        for (String outcome : List.of("COMPLETED", "SKIPPED", "FAILED", "UNHANDLED")) {
            org.assertj.core.api.Assertions.assertThat(registry.get("memoryos.operation.outcomes")
                    .tags("workload", workload, "outcome", outcome).counter().count())
                    .isEqualTo(outcome.equals(expected) ? 1 : 0);
        }
        org.assertj.core.api.Assertions.assertThat(registry.find("memoryos.operation.outcomes").counters()).allSatisfy(meter ->
                org.assertj.core.api.Assertions.assertThat(meter.getId().getTags())
                        .extracting(io.micrometer.core.instrument.Tag::getKey).containsOnly("workload", "outcome"));
    }

    private void assertWait(String workload, long count) {
        var timer = registry.get("memoryos.operation.initial.queue.wait").tag("workload", workload).timer();
        org.assertj.core.api.Assertions.assertThat(timer.count()).isEqualTo(count);
        org.assertj.core.api.Assertions.assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(count * 2.0);
    }

}
