package io.memoryos.api.chat;

import com.embabel.agent.openai.CapabilityAwareOpenAiOptionsConverter;
import com.embabel.agent.openai.ModelCapabilities;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.PricingModel;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import io.memoryos.chat.execution.ChatExecutionProperties;
import io.memoryos.chat.execution.ChatModelBinding;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import java.util.List;

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
        return OpenAIOkHttpClientAsync.builder().apiKey(key).baseUrl(baseUrl).maxRetries(0).timeout(limits.deadline()).build();
    }

    @Bean(destroyMethod = "close")
    @Lazy
    OpenAIClient chatOpenAiSyncClient(@Value("${memoryos.chat.provider.api-key:}") String key,
                                      @Value("${memoryos.chat.provider.base-url:https://api.openai.com/v1}") String baseUrl,
                                      ChatExecutionProperties limits) {
        requireCredential(key);
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

    @Bean
    SpringAiLlmService chatLlmService(@Lazy ChatModel chatProviderModel,
                                      @Value("${memoryos.chat.persona.model:gpt-5-mini}") String model, ChatExecutionProperties limits,
                                      @Value("${memoryos.chat.provider.input-price-per-million:-1}") double inputPrice,
                                      @Value("${memoryos.chat.provider.output-price-per-million:-1}") double outputPrice) {
        if (!model.startsWith("gpt-5"))
            throw new IllegalArgumentException("Chat currently requires the verified GPT-5 options family");
        if (!Double.isFinite(inputPrice) || !Double.isFinite(outputPrice) || inputPrice < -1 || outputPrice < -1
                || ((inputPrice < 0) != (outputPrice < 0)))
            throw new IllegalArgumentException("Invalid Chat pricing configuration");
        var pricing = inputPrice < 0 ? null : PricingModel.usdPer1MTokens(inputPrice, outputPrice);
        if (pricing == null && limits.costBudgetUsd() < Double.MAX_VALUE)
            throw new IllegalArgumentException("A Chat cost cap requires configured model pricing");
        return new SpringAiLlmService(model, "OpenAI", chatProviderModel,
                new CapabilityAwareOpenAiOptionsConverter(ModelCapabilities.GPT5_FAMILY), null, List.of(), pricing);
    }

    @Bean
    ChatModelBinding chatModelBinding(SpringAiLlmService chatLlmService) {
        return new ChatModelBinding(chatLlmService, OpenAiChatProviderConfiguration::withoutTools);
    }

    static Prompt withoutTools(Prompt prompt) {
        if (!(prompt.getOptions() instanceof OpenAiChatOptions options))
            throw new IllegalArgumentException("CHAT_UNSUPPORTED_OPTIONS");
        return new Prompt(prompt.getInstructions(), options.mutate().toolCallbacks(List.of()).toolChoice(null).build());
    }

    private static void requireCredential(String key) {
        if (key.isBlank()) throw new IllegalStateException("Chat provider credential is not configured");
    }
}
