package io.memoryos.api.chat;

import io.memoryos.chat.catalog.ChatModelClients;
import io.memoryos.chat.catalog.ChatModelResolver;
import io.memoryos.chat.catalog.ChatProviderAdapter;
import io.memoryos.chat.catalog.ChatProviderAdapters;
import io.memoryos.chat.catalog.ModelCatalogService;
import io.memoryos.chat.catalog.ModelSettings;
import io.memoryos.chat.catalog.ProviderCredentials;
import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.execution.ChatExecutionProperties;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.ModelCatalogRepository;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.TenantAccessResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ChatModelCatalogConfiguration {
    @Bean
    OpenAiChatProviderAdapter openAiChatProviderAdapter(ObservationRegistry observations, MeterRegistry meters) {
        return new OpenAiChatProviderAdapter(observations, meters);
    }
    @Bean
    ChatProviderAdapters chatProviderAdapters(List<ChatProviderAdapter> adapters) { return new ChatProviderAdapters(adapters); }
    @Bean(destroyMethod = "close")
    ChatModelClients chatModelClients(@Value("${memoryos.chat.catalog.max-clients:32}") int capacity) { return new ChatModelClients(capacity); }
    @Bean
    ModelCatalogService.Deployment chatDeploymentModel(PersonaProperties persona, ChatExecutionProperties limits,
            @Value("${memoryos.chat.provider.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${memoryos.chat.provider.input-price-per-million:-1}") double input,
            @Value("${memoryos.chat.provider.output-price-per-million:-1}") double output,
            @Value("${memoryos.chat.provider.max-completion-tokens:#{null}}") @Nullable Boolean maxCompletionTokens,
            @Value("${memoryos.chat.provider.tool-calling:#{null}}") @Nullable Boolean toolCalling,
            @Value("${memoryos.chat.provider.vision:#{null}}") @Nullable Boolean vision,
            @Value("${memoryos.chat.provider.reasoning:#{null}}") @Nullable Boolean reasoning) {
        if (!Double.isFinite(input) || !Double.isFinite(output) || input < -1 || output < -1 || ((input < 0) != (output < 0)))
            throw new IllegalArgumentException("Invalid Chat pricing configuration");
        var pricing = input < 0 ? null : new ModelSettings.Pricing(input, output);
        if (pricing == null && limits.costBudgetUsd() < Double.MAX_VALUE)
            throw new IllegalArgumentException("A Chat cost budget requires configured deployment model pricing");
        // Compatibility import for the existing deployment. Catalog adapters never infer all model options from a name.
        boolean gpt5 = persona.getModel().startsWith("gpt-5");
        var settings = new ModelSettings(limits.contextTokenLimit() + limits.maxOutputTokens(), limits.maxOutputTokens(),
                new ModelSettings.Capabilities(true, toolCalling == null ? gpt5 : toolCalling,
                        vision == null ? gpt5 : vision, reasoning == null ? gpt5 : reasoning),
                Map.of("maxCompletionTokens", maxCompletionTokens == null ? gpt5 : maxCompletionTokens), pricing);
        return new ModelCatalogService.Deployment(baseUrl, persona.getModel(), settings);
    }
    @Bean
    ModelCatalogService modelCatalogService(ModelCatalogRepository catalog, JdbcChatRepository chats, TenantAccessResolver tenants,
            IamAuthorization authorization, ChatProviderAdapters adapters, ProviderCredentials credentials,
            PersonaProperties persona, ModelCatalogService.Deployment deployment) {
        return new ModelCatalogService(catalog, chats, tenants, authorization, adapters, credentials, persona, deployment);
    }
    @Bean
    ChatModelResolver chatModelResolver(ModelCatalogService catalog, ChatProviderAdapters adapters, ProviderCredentials credentials,
                                       ChatModelClients clients, ChatExecutionProperties limits) {
        return new ChatModelResolver(catalog, adapters, credentials, clients, limits);
    }
}
