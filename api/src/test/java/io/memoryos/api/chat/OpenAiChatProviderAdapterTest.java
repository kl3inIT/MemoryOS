package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.embabel.common.ai.model.LlmOptions;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ModelSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class OpenAiChatProviderAdapterTest {
    @Test
    void nativeConverterUsesExplicitOptionsFamilyInsteadOfRequiringAGpt5Name() {
        var generic = settings(Map.of("temperature", 0.3, "maxCompletionTokens", false), false);
        var reasoning = settings(Map.of("maxCompletionTokens", true, "reasoningEffort", "low"), true);
        var meters = new SimpleMeterRegistry();
        try {
            var adapter = new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, meters);
            adapter.validate("http://model.internal/v1", "custom-deployment-name", generic);
            adapter.validate("https://api.example/v1", "reasoning-deployment-name", reasoning);
        } finally { meters.close(); }
        var first = OpenAiChatProviderAdapter.binding("custom-deployment-name", generic, mock(ChatModel.class));
        var options = (OpenAiChatOptions) first.service().convertOptions(new LlmOptions().withMaxTokens(100));
        assertEquals("custom-deployment-name", options.getModel());
        assertEquals(100, options.getMaxTokens());
        assertNull(options.getMaxCompletionTokens());
        assertEquals(0.3, options.getTemperature());
        assertNull(first.service().getPricingModel(), "Unknown pricing must not be reported as free");
        var second = OpenAiChatProviderAdapter.binding("reasoning-deployment-name", reasoning, mock(ChatModel.class));
        var secondOptions = (OpenAiChatOptions) second.service().convertOptions(new LlmOptions().withMaxTokens(100));
        assertNull(secondOptions.getMaxTokens());
        assertEquals(100, secondOptions.getMaxCompletionTokens());
        assertNull(secondOptions.getTemperature());
        assertEquals("low", secondOptions.getReasoningEffort());
        assertTrue(second.service().supportsThinking());
        var finalOptions = (OpenAiChatOptions) second.finalRequest().apply(new Prompt("Final", secondOptions)).getOptions();
        assertNotNull(finalOptions);
        assertNotNull(finalOptions.getToolCallbacks());
        assertTrue(finalOptions.getToolCallbacks().isEmpty());
        assertNull(finalOptions.getToolChoice());
    }

    @Test
    void rejectsUnknownOrMalformedOptionsBeforeAnyProviderRequest() {
        var meters = new SimpleMeterRegistry();
        try {
            var adapter = new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, meters);
            for (var options : java.util.List.<Map<String, Object>>of(Map.of("apiKey", "must-not-be-an-option"),
                    Map.of("temperature", "hot"), Map.of("temperature", 3), Map.of("topP", Double.NaN),
                    Map.of("maxCompletionTokens", "true"), Map.of("reasoningEffort", "unlimited"),
                    Map.of("maxCompletionTokens", true, "temperature", 0.5))) {
                assertThrows(ChatException.class, () -> adapter.validate("http://model.internal/v1", "model", settings(options, true)));
            }
        } finally { meters.close(); }
    }
    private static ModelSettings settings(Map<String, Object> options, boolean reasoning) {
        return new ModelSettings(8192, 512, new ModelSettings.Capabilities(true, true, false, reasoning), options, null);
    }
}
