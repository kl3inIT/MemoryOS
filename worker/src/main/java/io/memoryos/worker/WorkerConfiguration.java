package io.memoryos.worker;

import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.connector.ConnectorSyncPort;
import io.memoryos.connector.GoogleDriveSelectionProcessor;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.document.ExtractionArtifactPort;
import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.ingestion.application.DefaultIngestionCoordinator;
import io.memoryos.ingestion.application.SearchIngestionCoordinator;
import io.memoryos.ingestion.application.SelectionValidationProcessor;
import io.memoryos.ingestion.application.SourceSyncProcessor;
import io.memoryos.ingestion.persistence.JdbcSearchWorkRepository;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.StoredObjectRegistry;
import io.memoryos.retrieval.SearchIndex;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class WorkerConfiguration {
    @Bean(destroyMethod = "close")
    ScheduledExecutorService claimLeaseScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("memoryos-claim-lease-", 0).factory()
        );
    }

    @Bean
    IngestionCoordinator ingestionCoordinator(
            ConnectorIndexingPort indexingPort,
            ConnectorCleanupPort cleanupPort,
            DocumentCommandPort documents,
            SourceContentExtractor extractor,
            ObjectStorage storage,
            StoredObjectRegistry storedObjects,
            PlatformTransactionManager transactionManager,
            ScheduledExecutorService claimLeaseScheduler,
            ExtractionArtifactPort artifacts,
            MeterRegistry registry,
            ConnectorSyncPort sourceSync,
            GoogleDriveSelectionProcessor selections,
            JdbcSearchWorkRepository searchWork,
            DocumentChunkPort chunks,
            SearchIndex searchIndex
    ) {
        var ingestion = new DefaultIngestionCoordinator(
                indexingPort,
                cleanupPort,
                documents,
                extractor,
                storage,
                storedObjects,
                new TransactionTemplate(transactionManager),
                claimLeaseScheduler,
                artifacts,
                registry,
                new SourceSyncProcessor(sourceSync, claimLeaseScheduler, registry),
                new SelectionValidationProcessor(selections, claimLeaseScheduler, registry)
        );
        var search = new SearchIngestionCoordinator(searchWork, chunks, searchIndex,
                new TransactionTemplate(transactionManager), claimLeaseScheduler, registry);
        return delivery -> delivery.workload() == OperationWorkload.SEARCH
                ? search.process(delivery) : ingestion.process(delivery);
    }
}
