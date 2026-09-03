package io.memoryos.worker;

import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.ingestion.application.DefaultIngestionCoordinator;

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
            PlatformTransactionManager transactionManager,
            ScheduledExecutorService claimLeaseScheduler
    ) {
        return new DefaultIngestionCoordinator(
                indexingPort,
                cleanupPort,
                documents,
                extractor,
                new TransactionTemplate(transactionManager),
                claimLeaseScheduler
        );
    }
}
