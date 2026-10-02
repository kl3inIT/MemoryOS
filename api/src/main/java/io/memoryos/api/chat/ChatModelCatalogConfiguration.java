package io.memoryos.api.chat;

import io.memoryos.ai.ModelClients;
import io.memoryos.ai.ModelResolver;
import io.memoryos.ai.ProviderAdapter;
import io.memoryos.ai.ProviderAdapterRegistry;
import io.memoryos.ai.ModelCatalogService;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.chat.ChatExecutionProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ChatModelCatalogConfiguration {
    @Bean
    ProviderAdapterRegistry chatProviderAdapters(List<ProviderAdapter> adapters) { return new ProviderAdapterRegistry(adapters); }
    @Bean(destroyMethod = "close")
    ModelClients chatModelClients(@Value("${memoryos.chat.catalog.max-clients:32}") int capacity) { return new ModelClients(capacity); }
    @Bean
    ModelResolver chatModelResolver(ModelCatalogService catalog, ProviderAdapterRegistry adapters, ProviderCredentials credentials,
                                       ModelClients clients, ChatExecutionProperties limits) {
        return new ModelResolver(catalog, adapters, credentials, clients, limits.providerReadTimeout(), limits.costCapped());
    }
}
