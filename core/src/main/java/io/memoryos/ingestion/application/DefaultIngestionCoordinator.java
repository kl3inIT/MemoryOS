package io.memoryos.ingestion.application;

import io.memoryos.connector.CleanupWork;
import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.connector.IndexWork;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.StoredObjectRegistry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

public class DefaultIngestionCoordinator implements IngestionCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultIngestionCoordinator.class);
    private static final int MAX_PROCESSING_ATTEMPTS = 3;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(5);
    private static final long LEASE_RENEWAL_SECONDS = 30;

    private final ConnectorIndexingPort indexingPort;
    private final ConnectorCleanupPort cleanupPort;
    private final DocumentCommandPort documents;
    private final SourceContentExtractor extractor;
    private final ObjectStorage storage;
    private final StoredObjectRegistry storedObjects;
    private final TransactionTemplate transactions;
    private final ScheduledExecutorService leaseScheduler;
    private final IngestionMetrics metrics;

    public DefaultIngestionCoordinator(
            ConnectorIndexingPort indexingPort,
            ConnectorCleanupPort cleanupPort,
            DocumentCommandPort documents,
            SourceContentExtractor extractor,
            ObjectStorage storage,
            StoredObjectRegistry storedObjects,
            TransactionTemplate transactions,
            ScheduledExecutorService leaseScheduler,
            io.micrometer.core.instrument.MeterRegistry registry
    ) {
        this.metrics = new IngestionMetrics(registry);
        this.indexingPort = Objects.requireNonNull(indexingPort, "indexingPort must not be null");
        this.cleanupPort = Objects.requireNonNull(cleanupPort, "cleanupPort must not be null");
        this.documents = Objects.requireNonNull(documents, "documents must not be null");
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.storedObjects = Objects.requireNonNull(storedObjects, "storedObjects must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.leaseScheduler = Objects.requireNonNull(leaseScheduler, "leaseScheduler must not be null");
    }

    @Override
    public Outcome process(OperationDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery must not be null");
        final Outcome outcome;
        try {
            outcome = processDelivery(delivery);
        } catch (RuntimeException exception) {
            metrics.unhandled(delivery.workload());
            throw exception;
        }
        metrics.completed(delivery.workload(), outcome);
        return outcome;
    }

    private Outcome processDelivery(OperationDelivery delivery) {
        return switch (delivery.workload()) {
            case INGESTION -> indexingPort.claim(
                            delivery.tenantId(),
                            delivery.operationId(),
                            delivery.deliveryId()
                    )
                    .map(this::processIndex).orElse(Outcome.SKIPPED);
            case CLEANUP -> cleanupPort.claim(
                            delivery.tenantId(),
                            delivery.operationId(),
                            delivery.deliveryId()
                    )
                    .map(this::processCleanup).orElse(Outcome.SKIPPED);
        };
    }

    private Outcome processIndex(IndexWork work) {
        metrics.firstClaim(io.memoryos.ingestion.OperationWorkload.INGESTION, work.initialQueueWait());
        ScheduledFuture<?> renewal = leaseScheduler.scheduleAtFixedRate(
                () -> renewIndexLease(work),
                LEASE_RENEWAL_SECONDS,
                LEASE_RENEWAL_SECONDS,
                TimeUnit.SECONDS
        );
        LOGGER.atInfo().addKeyValue("event", "ingestion.started")
                .addKeyValue("operation_id", work.operationId().value()).log("Indexing started");
        try {
            var expected = work.object().metadata();
            final io.memoryos.document.DocumentContent content;
            try (var objectContent = storage.open(work.object().key())) {
                if (!expected.equals(objectContent.metadata())) {
                    throw new IllegalStateException("stored object metadata changed after adoption");
                }
                content = extractor.extract(
                        objectContent.inputStream(),
                        expected.sizeBytes(),
                        work.object().filename()
                );
            }
            transactions.executeWithoutResult(ignored -> {
                var documentId = documents.publish(
                        work.tenantId(),
                        indexingPort.findMappedDocument(work).orElse(null),
                        content,
                        expected.checksum().value()
                );
                if (!indexingPort.complete(work, documentId)) {
                    throw new StaleIndexClaimException();
                }
            });
            LOGGER.atInfo().addKeyValue("event", "ingestion.completed")
                    .addKeyValue("operation_id", work.operationId().value()).log("Indexing completed");
            return Outcome.COMPLETED;
        } catch (StaleIndexClaimException exception) {
            indexingPort.supersede(work);
            LOGGER.atDebug().addKeyValue("event", "ingestion.publication.stale")
                    .addKeyValue("operation_id", work.operationId().value())
                    .log("Rolled back stale index publication");
            return Outcome.SKIPPED;
        } catch (ExtractionException exception) {
            LOGGER.atWarn().addKeyValue("event", "ingestion.extraction.failed")
                    .addKeyValue("operation_id", work.operationId().value())
                    .addKeyValue("error_code", "SOURCE_EXTRACTION_" + exception.failure().name())
                    .log("Extraction failed");
            if (!indexingPort.fail(work, "SOURCE_EXTRACTION_" + exception.failure().name())) {
                LOGGER.atDebug().addKeyValue("event", "ingestion.extraction.failure.stale")
                    .addKeyValue("operation_id", work.operationId().value())
                    .log("Ignored stale typed extraction failure");
            }
            return Outcome.FAILED;
        } catch (RuntimeException exception) {
            LOGGER.atWarn().addKeyValue("event", "ingestion.retry.requested")
                    .addKeyValue("operation_id", work.operationId().value())
                    .addKeyValue("error_type", exception.getClass().getName())
                    .log("Indexing failed; applying retry policy");
            if (!indexingPort.retry(
                    work,
                    "SOURCE_EXTRACTION_INTERNAL",
                    MAX_PROCESSING_ATTEMPTS,
                    RETRY_BACKOFF
            )) {
                LOGGER.atDebug().addKeyValue("event", "ingestion.retry.stale")
                    .addKeyValue("operation_id", work.operationId().value())
                    .log("Ignored stale internal extraction failure");
            }
            return Outcome.FAILED;
        } finally {
            renewal.cancel(false);
        }
    }

    private void renewIndexLease(IndexWork work) {
        try {
            indexingPort.renew(work);
        } catch (RuntimeException exception) {
            LOGGER.atWarn().addKeyValue("event", "ingestion.lease.renewal_failed")
                    .addKeyValue("operation_id", work.operationId().value())
                    .log("Index processing lease renewal failed; the next interval will retry");
        }
    }

    private Outcome processCleanup(CleanupWork work) {
        metrics.firstClaim(io.memoryos.ingestion.OperationWorkload.CLEANUP, work.initialQueueWait());
        ScheduledFuture<?> renewal = leaseScheduler.scheduleAtFixedRate(
                () -> renewCleanupLease(work),
                LEASE_RENEWAL_SECONDS,
                LEASE_RENEWAL_SECONDS,
                TimeUnit.SECONDS
        );
        try {
            for (var object : cleanupPort.objects(work)) {
                storedObjects.markDeletePending(work.tenantId(), object.object().id());
                storage.delete(object.object().key());
            }
            if (!cleanupPort.execute(work)) {
                LOGGER.atDebug().addKeyValue("event", "cleanup.completion.stale")
                    .addKeyValue("operation_id", work.operationId().value())
                    .log("Ignored stale cleanup completion");
                return Outcome.SKIPPED;
            }
            return Outcome.COMPLETED;
        } catch (RuntimeException exception) {
            LOGGER.atWarn().addKeyValue("event", "cleanup.retry.requested")
                    .addKeyValue("operation_id", work.operationId().value())
                    .addKeyValue("error_type", exception.getClass().getName())
                    .log("Cleanup failed; applying retry policy");
            if (!cleanupPort.retry(
                    work,
                    "SOURCE_CLEANUP_INTERNAL",
                    MAX_PROCESSING_ATTEMPTS,
                    RETRY_BACKOFF
            )) {
                LOGGER.atDebug().addKeyValue("event", "cleanup.retry.stale")
                    .addKeyValue("operation_id", work.operationId().value())
                    .log("Ignored stale cleanup failure");
            }
            return Outcome.FAILED;
        } finally {
            renewal.cancel(false);
        }
    }

    private void renewCleanupLease(CleanupWork work) {
        try {
            cleanupPort.renew(work);
        } catch (RuntimeException exception) {
            LOGGER.atWarn().addKeyValue("event", "cleanup.lease.renewal_failed")
                    .addKeyValue("operation_id", work.operationId().value())
                    .log("Cleanup processing lease renewal failed; the next interval will retry");
        }
    }

    private static final class StaleIndexClaimException extends RuntimeException {
    }
}
