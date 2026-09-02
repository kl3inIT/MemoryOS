package io.memoryos.worker;

import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.ingestion.application.DefaultIngestionCoordinator;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnableConfigurationProperties(WorkerProperties.class)
@Configuration(proxyBeanMethods = false)
class WorkerConfiguration {

    @Bean
    IngestionCoordinator ingestionCoordinator(
            ConnectorIndexingPort indexingPort,
            ConnectorCleanupPort cleanupPort,
            DocumentCommandPort documents,
            SourceContentExtractor extractor,
            PlatformTransactionManager transactionManager
    ) {
        return new DefaultIngestionCoordinator(
                indexingPort,
                cleanupPort,
                documents,
                extractor,
                new TransactionTemplate(transactionManager)
        );
    }
}
