package io.memoryos.ai.ocr;

import io.memoryos.ai.ModelClients;
import io.memoryos.ai.ModelLeases;
import io.memoryos.ai.ProviderAdapter;
import io.memoryos.ai.ProviderAdapterRegistry;
import io.memoryos.ai.ProviderConnections;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.ai.anthropic.AnthropicProviderConfiguration;
import io.memoryos.ai.openai.OpenAiProviderConfiguration;
import io.memoryos.ai.persistence.ModelCatalogRepository;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * What the Worker needs to read a Tenant's scans with the reader it chose: the connection service, the credential
 * handling it shares with every provider connection, and, for a reader that is a catalog model, the provider adapters
 * and their clients, with the catalog's entities and repositories. The Worker imports this class; it is deliberately not a component or a configuration, so the API,
 * which scans all
 * of {@code io.memoryos} and wires the same beans for Chat, does not register them twice.
 */
@EntityScan("io.memoryos.ai.persistence")
@EnableJpaRepositories("io.memoryos.ai.persistence")
@Import({OcrConnectionService.class, OcrPageReader.class, OcrServiceUsage.class, ProviderConnections.class,
        ProviderCredentials.class,
        ModelCatalogRepository.class, OpenAiProviderConfiguration.class, AnthropicProviderConfiguration.class})
public class OcrWorkerComponents {
    @Bean
    ProviderAdapterRegistry ocrProviderAdapters(List<ProviderAdapter> adapters) {
        return new ProviderAdapterRegistry(adapters);
    }

    /** One client a model the Tenant reads with; a Worker reads with one or two models, not a catalog of them. */
    @Bean(destroyMethod = "close")
    ModelClients ocrModelClients() {
        return new ModelClients(4);
    }

    /** A page is one answer of up to a few thousand tokens, so the read gap is longer than a Chat stream's. */
    @Bean
    ModelLeases ocrModelLeases(ProviderAdapterRegistry adapters, ProviderCredentials credentials, ModelClients clients,
            @Value("${memoryos.extraction.model-ocr.read-timeout:2m}") Duration readTimeout) {
        return new ModelLeases(adapters, credentials, clients, readTimeout);
    }
}
