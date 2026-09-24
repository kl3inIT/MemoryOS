package io.memoryos.worker;

import io.memoryos.chat.UserFileWorkPort;
import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.connector.ConnectorSyncPort;
import io.memoryos.connector.GoogleDriveSelectionProcessor;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.document.ExtractionArtifactPort;
import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.ingestion.application.DefaultIngestionCoordinator;
import io.memoryos.ingestion.application.SearchProjectionMaintenance;
import io.memoryos.ingestion.application.SelectionValidationProcessor;
import io.memoryos.ingestion.application.SourceSyncProcessor;
import io.memoryos.ingestion.application.UserFileIngestionCoordinator;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.StoredObjectRegistry;
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
            SearchProjectionMaintenance searchProjection,
            UserFileWorkPort userFiles,
            io.memoryos.ingestion.ChatFileExtractor chatFileExtractor,
            io.memoryos.connector.SharePointSelectionProcessor sharePointSelections
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
                new SelectionValidationProcessor(selections, sharePointSelections, claimLeaseScheduler, registry)
        );
        var search = searchProjection.coordinator(new TransactionTemplate(transactionManager), claimLeaseScheduler, registry);
        var files = new UserFileIngestionCoordinator(userFiles, chatFileExtractor, storage, artifacts, claimLeaseScheduler);
        return delivery -> switch (delivery.workload()) {
            case SEARCH -> search.process(delivery);
            case USER_FILE -> files.process(delivery);
            default -> ingestion.process(delivery);
        };
    }
}
