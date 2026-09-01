package io.memoryos.worker;

import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.document.DocumentCommandService;
import io.memoryos.ingestion.IndexingCoordinator;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.ingestion.application.DefaultIndexingCoordinator;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnableConfigurationProperties(WorkerProperties.class)
@Configuration(proxyBeanMethods = false)
class WorkerConfiguration {

    @Bean
    IndexingCoordinator indexingCoordinator(
            ConnectorIndexingPort indexingPort,
            ConnectorCleanupPort cleanupPort,
            DocumentCommandService documents,
            SourceContentExtractor extractor,
            PlatformTransactionManager transactionManager
    ) {
        return new DefaultIndexingCoordinator(
                indexingPort,
                cleanupPort,
                documents,
                extractor,
                new TransactionTemplate(transactionManager)
        );
    }
}
