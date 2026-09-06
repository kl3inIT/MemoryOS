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
    private final io.memoryos.document.ExtractionArtifactPort artifacts;

    public DefaultIngestionCoordinator(
            ConnectorIndexingPort indexingPort,
            ConnectorCleanupPort cleanupPort,
            DocumentCommandPort documents,
            SourceContentExtractor extractor,
            ObjectStorage storage,
            StoredObjectRegistry storedObjects,
            TransactionTemplate transactions,
            ScheduledExecutorService leaseScheduler,
            io.memoryos.document.ExtractionArtifactPort artifacts
    ) {
        this.indexingPort = Objects.requireNonNull(indexingPort, "indexingPort must not be null");
        this.cleanupPort = Objects.requireNonNull(cleanupPort, "cleanupPort must not be null");
        this.documents = Objects.requireNonNull(documents, "documents must not be null");
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.storedObjects = Objects.requireNonNull(storedObjects, "storedObjects must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.leaseScheduler = Objects.requireNonNull(leaseScheduler, "leaseScheduler must not be null");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts must not be null");
    }

    @Override
    public void process(OperationDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery must not be null");
        switch (delivery.workload()) {
            case INGESTION -> indexingPort.claim(
                            delivery.tenantId(),
                            delivery.operationId(),
                            delivery.deliveryId()
                    )
                    .ifPresent(this::processIndex);
            case CLEANUP -> cleanupPort.claim(
                            delivery.tenantId(),
                            delivery.operationId(),
                            delivery.deliveryId()
                    )
                    .ifPresent(this::processCleanup);
        }
    }

    private void processIndex(IndexWork work) {
        ScheduledFuture<?> renewal = leaseScheduler.scheduleAtFixedRate(
                () -> renewIndexLease(work),
                LEASE_RENEWAL_SECONDS,
                LEASE_RENEWAL_SECONDS,
                TimeUnit.SECONDS
        );
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
            var staged = artifacts.stage(work.tenantId(), content);
            transactions.executeWithoutResult(ignored -> {
                var documentId = documents.publish(
                        work.tenantId(),
                        indexingPort.findMappedDocument(work).orElse(null),
                        staged,
                        expected.checksum().value()
                );
                if (!indexingPort.complete(work, documentId)) {
                    throw new StaleIndexClaimException();
                }
            });
        } catch (StaleIndexClaimException exception) {
            indexingPort.supersede(work);
            LOGGER.debug("Rolled back stale index publication");
        } catch (ExtractionException exception) {
            if (!indexingPort.fail(work, "SOURCE_EXTRACTION_" + exception.failure().name())) {
                LOGGER.debug("Ignored stale typed extraction failure");
            }
        } catch (RuntimeException exception) {
            if (!indexingPort.retry(
                    work,
                    "SOURCE_EXTRACTION_INTERNAL",
                    MAX_PROCESSING_ATTEMPTS,
                    RETRY_BACKOFF
            )) {
                LOGGER.debug("Ignored stale internal extraction failure");
            }
        } finally {
            renewal.cancel(false);
        }
    }

    private void renewIndexLease(IndexWork work) {
        try {
            indexingPort.renew(work);
        } catch (RuntimeException exception) {
            LOGGER.warn("Index processing lease renewal failed; the next interval will retry");
        }
    }

    private void processCleanup(CleanupWork work) {
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
                LOGGER.debug("Ignored stale cleanup completion");
            }
        } catch (RuntimeException exception) {
            if (!cleanupPort.retry(
                    work,
                    "SOURCE_CLEANUP_INTERNAL",
                    MAX_PROCESSING_ATTEMPTS,
                    RETRY_BACKOFF
            )) {
                LOGGER.debug("Ignored stale cleanup failure");
            }
        } finally {
            renewal.cancel(false);
        }
    }

    private void renewCleanupLease(CleanupWork work) {
        try {
            cleanupPort.renew(work);
        } catch (RuntimeException exception) {
            LOGGER.warn("Cleanup processing lease renewal failed; the next interval will retry");
        }
    }

    private static final class StaleIndexClaimException extends RuntimeException {
    }
}
