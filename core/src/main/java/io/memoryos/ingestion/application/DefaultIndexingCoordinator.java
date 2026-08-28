package io.memoryos.ingestion.application;

import io.memoryos.connector.CleanupWork;
import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.connector.IndexWork;
import io.memoryos.document.DocumentCommandService;
import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.IndexingCoordinator;
import io.memoryos.ingestion.SourceContentExtractor;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.transaction.support.TransactionTemplate;

public class DefaultIndexingCoordinator implements IndexingCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultIndexingCoordinator.class);


    private final ConnectorIndexingPort indexingPort;
    private final ConnectorCleanupPort cleanupPort;
    private final DocumentCommandService documents;
    private final SourceContentExtractor extractor;
    private final TransactionTemplate transactions;

    public DefaultIndexingCoordinator(
            ConnectorIndexingPort indexingPort,
            ConnectorCleanupPort cleanupPort,
            DocumentCommandService documents,
            SourceContentExtractor extractor,
            TransactionTemplate transactions
    ) {
        this.indexingPort = Objects.requireNonNull(indexingPort, "indexingPort must not be null");
        this.cleanupPort = Objects.requireNonNull(cleanupPort, "cleanupPort must not be null");
        this.documents = Objects.requireNonNull(documents, "documents must not be null");
        this.extractor = Objects.requireNonNull(extractor, "extractor must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    @Override
    public void processAvailable(int batchSize) {
        for (IndexWork work : indexingPort.claim(batchSize)) {
            processIndex(work);
        }
        for (CleanupWork work : cleanupPort.claim(batchSize)) {
            processCleanup(work);
        }
    }

    private void processIndex(IndexWork work) {
        try {
            var extraction = extractor.extract(work.content(), work.filename());
            transactions.executeWithoutResult(ignored -> {
                var documentId = documents.publish(
                        work.organizationId(),
                        indexingPort.findMappedDocument(work).orElse(null),
                        new DocumentContent(
                                extraction.mediaType(),
                                extraction.title(),
                                extraction.normalizedText(),
                                extraction.metadata()
                        ),
                        work.sha256()
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
            if (!indexingPort.fail(work, "SOURCE_EXTRACTION_INTERNAL")) {
                LOGGER.debug("Ignored stale internal extraction failure");
            }
        }
    }

    private void processCleanup(CleanupWork work) {
        try {
            if (!cleanupPort.execute(work)) {
                LOGGER.debug("Ignored stale cleanup completion");
            }
        } catch (RuntimeException exception) {
            if (!cleanupPort.fail(work, "SOURCE_CLEANUP_INTERNAL")) {
                LOGGER.debug("Ignored stale cleanup failure");
            }
        }
    }
    private static final class StaleIndexClaimException extends RuntimeException {
    }

}
