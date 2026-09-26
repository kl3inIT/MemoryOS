package io.memoryos.api.chat;

import io.memoryos.chat.PersonaProperties;
import io.memoryos.ai.ModelClients;
import io.memoryos.ai.ModelResolver;
import io.memoryos.ai.ProviderAdapter;
import io.memoryos.ai.ProviderAdapters;
import io.memoryos.ai.ModelCatalogService;
import io.memoryos.ai.ModelSettings;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.chat.ChatExecutionProperties;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ChatModelCatalogConfiguration {
    @Bean
    ProviderAdapters chatProviderAdapters(List<ProviderAdapter> adapters) { return new ProviderAdapters(adapters); }
    @Bean(destroyMethod = "close")
    ModelClients chatModelClients(@Value("${memoryos.chat.catalog.max-clients:32}") int capacity) { return new ModelClients(capacity); }
    @Bean
    ModelCatalogService.Deployment chatDeploymentModel(PersonaProperties persona, ChatExecutionProperties limits, ProviderAdapters adapters,
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
        // Compatibility import for the existing deployment. The installed catalog supplies the model's own limits,
        // capabilities and prices (MEM-130): the execution limits are runtime bounds, never the model's context window.
        var known = ModelResolver.findKnown(persona.getModel(), adapters.require("openai").knownModels());
        // A reasoning model takes max_completion_tokens and no sampling temperature. The catalog says which models
        // reason; the gpt-5 name is the fallback only for a model it does not know.
        boolean reasoningFamily = known != null ? known.capabilities().reasoning() : persona.getModel().startsWith("gpt-5");
        // A model the catalog does not know takes Onyx's defaults, as a discovered one does: a 32,000-token window
        // and no output cap.
        int contextWindow = known != null ? known.contextWindow() : ModelResolver.FALLBACK_CONTEXT_WINDOW;
        Integer maxOutput = known != null ? known.maxOutputTokens() : null;
        boolean defaultCapability = known == null && reasoningFamily;
        var settings = new ModelSettings(contextWindow, maxOutput,
                new ModelSettings.Capabilities(true,
                        toolCalling != null ? toolCalling : known != null ? known.capabilities().toolCalling() : defaultCapability,
                        vision != null ? vision : known != null ? known.capabilities().vision() : defaultCapability,
                        reasoning != null ? reasoning : known != null ? known.capabilities().reasoning() : defaultCapability),
                Map.of("maxCompletionTokens", maxCompletionTokens == null ? reasoningFamily : maxCompletionTokens),
                pricing != null ? pricing : known != null ? known.pricing() : null, "openai-o200k-v1");
        if (settings.pricing() == null && limits.costCapped())
            throw new IllegalArgumentException("A Chat cost budget requires configured deployment model pricing");
        return new ModelCatalogService.Deployment(baseUrl, persona.getModel(), settings);
    }
    @Bean
    ModelResolver chatModelResolver(ModelCatalogService catalog, ProviderAdapters adapters, ProviderCredentials credentials,
                                       ModelClients clients, ChatExecutionProperties limits) {
        return new ModelResolver(catalog, adapters, credentials, clients, limits.providerReadTimeout(), limits.costCapped());
    }
}
