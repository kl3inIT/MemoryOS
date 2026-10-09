package io.memoryos.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.connector.CleanupWork;
import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.connector.IndexWork;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.document.ExtractionArtifactPort;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.objectstorage.StoredObjectRegistry;
import io.memoryos.shared.TenantId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionTemplate;

class DefaultIngestionCoordinatorTest {
    private final SimpleMeterRegistry registry =
            new SimpleMeterRegistry();

    @Test
    void handledCleanupFailureReportsFailedAndKeepsDatabaseRetry() {
        var cleanup = mock(ConnectorCleanupPort.class);
        var scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        Mockito.doReturn(renewal).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), eq(30L), eq(30L), eq(TimeUnit.SECONDS));
        var delivery = new OperationDelivery(new TenantId(UUID.randomUUID()), OperationWorkload.CLEANUP,
                new SourceOperationId(UUID.randomUUID()), UUID.randomUUID());
        var work = new CleanupWork(delivery.operationId(), delivery.tenantId(), SourceOperationType.DELETE_SOURCE,
                new SourceId(UUID.randomUUID()), null, UUID.randomUUID(), Duration.ofSeconds(2));
        when(cleanup.claim(delivery.tenantId(), delivery.operationId(), delivery.deliveryId())).thenReturn(Optional.of(work));
        when(cleanup.objects(work)).thenThrow(new IllegalStateException("test failure"));
        var coordinator = new DefaultIngestionCoordinator(mock(ConnectorIndexingPort.class), cleanup,
                mock(DocumentCommandPort.class), mock(SourceContentExtractor.class), mock(ObjectStorage.class),
                mock(StoredObjectRegistry.class), mock(TransactionTemplate.class), scheduler,
                mock(ExtractionArtifactPort.class), registry, mock(SourceSyncProcessor.class),
                mock(SelectionValidationProcessor.class));

        assertThat(coordinator.process(delivery))
                .isEqualTo(IngestionCoordinator.Outcome.FAILED);
        assertOutcome("CLEANUP", "FAILED");
        assertWait("CLEANUP", 1);
        verify(cleanup).retry(eq(work), eq("SOURCE_CLEANUP_INTERNAL"), any(), any(), eq(3), eq(Duration.ofSeconds(5)));
        verify(renewal).cancel(false);
    }

    @ParameterizedTest
    @EnumSource(value = ExtractionFailure.class, mode = EnumSource.Mode.EXCLUDE, names = "CONNECTION_FAILED")
    void aFailureOfTheDocumentFailsItAtOnce(ExtractionFailure failure) throws Exception {
        var indexing = mock(ConnectorIndexingPort.class);
        var work = extractionFails(indexing, failure, 1);

        verify(indexing).fail(eq(work), eq("SOURCE_EXTRACTION_" + failure.name()), eq("test failure"), any());
        verify(indexing, Mockito.never()).retry(any(), any(), any(), any(), ArgumentMatchers.anyInt(), any());
        assertUnavailable(0, 0);
    }

    @ParameterizedTest
    @CsvSource({"1,PT30S", "2,PT2M", "3,PT5M", "4,PT10M", "5,PT15M"})
    void anUnreachableExtractionServiceIsAskedAgainAfterALongerWaitEachTime(int attempt, Duration wait)
            throws Exception {
        var indexing = mock(ConnectorIndexingPort.class);
        when(indexing.retry(any(), any(), any(), any(), ArgumentMatchers.anyInt(), any())).thenReturn(true);
        var work = extractionFails(indexing, ExtractionFailure.CONNECTION_FAILED, attempt);

        verify(indexing).retry(eq(work), eq("SOURCE_EXTRACTION_CONNECTION_FAILED"), eq("test failure"), any(),
                eq(6), eq(wait));
        verify(indexing, Mockito.never()).fail(any(), any(), any(), any());
        assertUnavailable(1, 0);
    }

    @Test
    void theSixthUnreachableAttemptIsCountedAsExhausted() throws Exception {
        var indexing = mock(ConnectorIndexingPort.class);
        when(indexing.retry(any(), any(), any(), any(), ArgumentMatchers.anyInt(), any())).thenReturn(true);
        var work = extractionFails(indexing, ExtractionFailure.CONNECTION_FAILED, 6);

        // The repository fails the attempt once the budget of six is spent; the wait is then unused.
        verify(indexing).retry(eq(work), eq("SOURCE_EXTRACTION_CONNECTION_FAILED"), eq("test failure"), any(),
                eq(6), any());
        assertUnavailable(0, 1);
    }

    @Test
    void aStaleUnreachableAttemptIsNotCounted() throws Exception {
        var indexing = mock(ConnectorIndexingPort.class);
        extractionFails(indexing, ExtractionFailure.CONNECTION_FAILED, 1);

        assertUnavailable(0, 0);
    }

    /** Runs one claimed index delivery whose extraction throws {@code failure}; returns the claimed work. */
    private IndexWork extractionFails(ConnectorIndexingPort indexing, ExtractionFailure failure, int attempt)
            throws Exception {
        var scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> renewal = mock(ScheduledFuture.class);
        Mockito.doReturn(renewal).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), eq(30L), eq(30L), eq(TimeUnit.SECONDS));
        var delivery = new OperationDelivery(new TenantId(UUID.randomUUID()), OperationWorkload.INGESTION,
                new SourceOperationId(UUID.randomUUID()), UUID.randomUUID());
        var reference = mock(StoredObjectReference.class);
        var metadata = mock(ObjectMetadata.class);
        when(reference.metadata()).thenReturn(metadata);
        var work = new IndexWork(delivery.operationId(), delivery.tenantId(),
                UUID.randomUUID(), new SourceId(UUID.randomUUID()), null, UUID.randomUUID(), reference,
                SourceInputDescriptor.binary(), Duration.ofSeconds(2), attempt);
        when(indexing.claim(delivery.tenantId(), delivery.operationId(), delivery.deliveryId())).thenReturn(Optional.of(work));
        var storage = mock(ObjectStorage.class);
        var content = mock(ObjectContent.class);
        when(storage.open(reference.key())).thenReturn(content);
        when(content.metadata()).thenReturn(metadata);
        var extractor = mock(SourceContentExtractor.class);
        when(extractor.extract(content.inputStream(), metadata.sizeBytes(), reference.filename(), work.input()))
                .thenThrow(new ExtractionException(
                        failure, "test failure"));
        var coordinator = new DefaultIngestionCoordinator(indexing, mock(ConnectorCleanupPort.class),
                mock(DocumentCommandPort.class), extractor, storage, mock(StoredObjectRegistry.class),
                mock(TransactionTemplate.class), scheduler,
                mock(ExtractionArtifactPort.class), registry, mock(SourceSyncProcessor.class),
                mock(SelectionValidationProcessor.class));

        assertThat(coordinator.process(delivery))
                .isEqualTo(IngestionCoordinator.Outcome.FAILED);
        assertOutcome("INGESTION", "FAILED");
        assertWait("INGESTION", 1);
        verify(content).close();
        verify(renewal).cancel(false);
        return work;
    }

    private void assertUnavailable(int retryScheduled, int exhausted) {
        assertThat(registry.get("memoryos.extraction.unavailable").tag("outcome", "retry_scheduled").counter().count())
                .isEqualTo(retryScheduled);
        assertThat(registry.get("memoryos.extraction.unavailable").tag("outcome", "exhausted").counter().count())
                .isEqualTo(exhausted);
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
                UUID.fromString("50000000-0000-0000-0000-000000000052"), Duration.ofSeconds(2)
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
                mock(ExtractionArtifactPort.class), registry,
                mock(SourceSyncProcessor.class), mock(SelectionValidationProcessor.class)
        );

        coordinator.process(new OperationDelivery(tenantId, OperationWorkload.CLEANUP, operationId, deliveryId));

        assertOutcome("CLEANUP", "COMPLETED");
        assertWait("CLEANUP", 1);
        verify(cleanup).renew(work);
        verify(renewal).cancel(false);
    }

    @ParameterizedTest
    @EnumSource(value = OperationWorkload.class, names = {"INGESTION", "CLEANUP"})
    void missingClaimCountsSkippedWithoutQueueWait(OperationWorkload workload) {
        var coordinator = new DefaultIngestionCoordinator(mock(ConnectorIndexingPort.class), mock(ConnectorCleanupPort.class),
                mock(DocumentCommandPort.class), mock(SourceContentExtractor.class), mock(ObjectStorage.class),
                mock(StoredObjectRegistry.class), mock(TransactionTemplate.class), mock(ScheduledExecutorService.class),
                mock(ExtractionArtifactPort.class), registry, mock(SourceSyncProcessor.class),
                mock(SelectionValidationProcessor.class));
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
                mock(ExtractionArtifactPort.class), registry, mock(SourceSyncProcessor.class),
                mock(SelectionValidationProcessor.class));
        assertThatThrownBy(() -> coordinator.process(new OperationDelivery(
                new TenantId(UUID.randomUUID()), OperationWorkload.INGESTION,
                new SourceOperationId(UUID.randomUUID()), UUID.randomUUID()))).isSameAs(failure);
        assertOutcome("INGESTION", "UNHANDLED");
        assertWait("INGESTION", 0);
    }

    @Test
    void metricRecordingFailureDoesNotChangeBusinessOutcome() {
        var failingRegistry = new SimpleMeterRegistry() {
            @NullMarked
            @Override
            protected Counter newCounter(Meter.Id id) {
                var counter = mock(Counter.class);
                Mockito.doThrow(new IllegalStateException("test metric failure")).when(counter).increment();
                return counter;
            }
        };
        var coordinator = new DefaultIngestionCoordinator(mock(ConnectorIndexingPort.class), mock(ConnectorCleanupPort.class),
                mock(DocumentCommandPort.class), mock(SourceContentExtractor.class), mock(ObjectStorage.class),
                mock(StoredObjectRegistry.class), mock(TransactionTemplate.class), mock(ScheduledExecutorService.class),
                mock(ExtractionArtifactPort.class), failingRegistry, mock(SourceSyncProcessor.class),
                mock(SelectionValidationProcessor.class));
        assertThat(coordinator.process(new OperationDelivery(new TenantId(UUID.randomUUID()),
                OperationWorkload.INGESTION, new SourceOperationId(UUID.randomUUID()), UUID.randomUUID())))
                .isEqualTo(IngestionCoordinator.Outcome.SKIPPED);
    }

    @Test
    void retryClaimOmitsWaitAndClockRollbackClampsToZero() {
        var metrics = new IngestionMetrics(registry);
        metrics.firstClaim(OperationWorkload.INGESTION, null);
        assertWait("INGESTION", 0);
        metrics.firstClaim(OperationWorkload.INGESTION, Duration.ofSeconds(-1));
        var timer = registry.get("memoryos.operation.initial.queue.wait").tag("workload", "INGESTION").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isZero();
    }

    private void assertOutcome(String workload, String expected) {
        for (String outcome : List.of("COMPLETED", "SKIPPED", "FAILED", "UNHANDLED")) {
            assertThat(registry.get("memoryos.operation.outcomes")
                    .tags("workload", workload, "outcome", outcome).counter().count())
                    .isEqualTo(outcome.equals(expected) ? 1 : 0);
        }
        assertThat(registry.find("memoryos.operation.outcomes").counters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags())
                        .extracting(Tag::getKey).containsOnly("workload", "outcome"));
    }

    private void assertWait(String workload, long count) {
        var timer = registry.get("memoryos.operation.initial.queue.wait").tag("workload", workload).timer();
        assertThat(timer.count()).isEqualTo(count);
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(count * 2.0);
    }

}
