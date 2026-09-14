package io.memoryos.api.chat;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import io.memoryos.chat.execution.ChatExecutionProperties;
import io.memoryos.chat.catalog.ModelCatalogService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import java.util.List;
import java.net.URI;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * One supported protocol, using native Spring AI clients and Embabel service/options.
 */
@Configuration(proxyBeanMethods = false)
class OpenAiChatProviderConfiguration {
    @Bean(destroyMethod = "close")
    @Lazy
    OpenAIClientAsync chatOpenAiClient(@Value("${memoryos.chat.provider.api-key:}") String key,
                                       @Value("${memoryos.chat.provider.base-url:https://api.openai.com/v1}") String baseUrl,
                                       ChatExecutionProperties limits) {
        requireCredential(key);
        requireEndpoint(baseUrl);
        return OpenAIOkHttpClientAsync.builder().apiKey(key).baseUrl(baseUrl).maxRetries(0).timeout(limits.deadline()).build();
    }

    @Bean(destroyMethod = "close")
    @Lazy
    OpenAIClient chatOpenAiSyncClient(@Value("${memoryos.chat.provider.api-key:}") String key,
                                      @Value("${memoryos.chat.provider.base-url:https://api.openai.com/v1}") String baseUrl,
                                      ChatExecutionProperties limits) {
        requireCredential(key);
        requireEndpoint(baseUrl);
        return OpenAIOkHttpClient.builder().apiKey(key).baseUrl(baseUrl).maxRetries(0).timeout(limits.deadline()).build();
    }

    @Bean
    @Lazy
    ChatModel chatProviderModel(@Lazy OpenAIClientAsync chatOpenAiClient, @Lazy OpenAIClient chatOpenAiSyncClient,
                                @Value("${memoryos.chat.provider.api-key:}") String key,
                                ObservationRegistry observations, MeterRegistry meters) {
        requireCredential(key);
        return OpenAiChatModel.builder().options(OpenAiChatOptions.builder().apiKey(key).maxRetries(0).build())
                .openAiClient(chatOpenAiSyncClient).openAiClientAsync(chatOpenAiClient)
                .observationRegistry(observations).meterRegistry(meters).build();
    }

    // Embabel's platform default metadata; Chat turns select their explicit catalog service.
    @Bean
    SpringAiLlmService chatLlmService(@Lazy ChatModel chatProviderModel,
                                     ModelCatalogService.Deployment deployment) {
        return OpenAiChatProviderAdapter.binding(deployment.modelName(), deployment.settings(), chatProviderModel).service();
    }

    static Prompt withoutTools(Prompt prompt) {
        if (!(prompt.getOptions() instanceof OpenAiChatOptions options))
            throw new IllegalArgumentException("CHAT_UNSUPPORTED_OPTIONS");
        return new Prompt(prompt.getInstructions(), options.mutate().toolCallbacks(List.of()).toolChoice(null).build());
    }

    private static void requireCredential(String key) {
        if (key.isBlank()) throw new IllegalStateException("Chat provider credential is not configured");
    }

    private static void requireEndpoint(String baseUrl) {
        URI endpoint;
        try { endpoint = URI.create(baseUrl); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid Chat provider endpoint"); }
        // Server-owned configuration may address an HTTP provider on an internal deployment network.
        if (!("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme())) || endpoint.getHost() == null
                || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null)
            throw new IllegalArgumentException("Chat provider endpoint must use HTTP(S) without credentials, query or fragment");
    }
}
