package io.memoryos.ingestion;

import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.connector.ConnectorSyncPort;
import io.memoryos.connector.SourceSelectionProcessor;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.document.ExtractionArtifactPort;
import io.memoryos.ingestion.application.DefaultIngestionCoordinator;
import io.memoryos.ingestion.application.SelectionValidationProcessor;
import io.memoryos.ingestion.application.SourceSyncProcessor;
import io.memoryos.ingestion.application.UserFileIngestionCoordinator;
import io.memoryos.library.UserFileWorkPort;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.StoredObjectRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Worker runs the whole ingestion module, and the one {@link IngestionCoordinator} a delivery is handed to:
 * document and source work, search projection work and uploaded file work, each by its workload. The Worker imports
 * this class and scans nothing of the module itself; it is deliberately not a component, so the API, which scans all
 * of {@code io.memoryos} and runs no deliveries, registers the module's beans only once and no coordinator.
 */
@ComponentScan
public class IngestionWorkerComponents {
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
            SourceSelectionProcessor selections,
            SearchProjectionMaintenance searchProjection,
            UserFileWorkPort userFiles,
            ChatFileExtractor chatFileExtractor
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
        var search = searchProjection.coordinator(new TransactionTemplate(transactionManager), claimLeaseScheduler, registry);
        var files = new UserFileIngestionCoordinator(userFiles, chatFileExtractor, storage, artifacts, claimLeaseScheduler);
        return delivery -> switch (delivery.workload()) {
            case SEARCH -> search.process(delivery);
            case USER_FILE -> files.process(delivery);
            default -> ingestion.process(delivery);
        };
    }
}
