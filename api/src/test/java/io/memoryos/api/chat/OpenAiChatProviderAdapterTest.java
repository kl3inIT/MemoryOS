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
    void helperReasoningOverrideSurvivesConversionAndSdkSerializationWithoutChangingAnswerOptions() throws Exception {
        var request = new java.util.concurrent.atomic.AtomicReference<tools.jackson.databind.JsonNode>();
        var mapper = new tools.jackson.databind.ObjectMapper();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body = """
                    {"id":"test","object":"chat.completion","created":1,"model":"configured-model",
                    "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        var meters = new SimpleMeterRegistry();
        try {
            var adapter = new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, meters);
            var connection = new io.memoryos.chat.catalog.ChatProviderAdapter.Connection("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture-only");
            for (String lowest : java.util.List.of("none", "minimal", "low")) {
                try (var client = adapter.create(connection, "configured-model", settings(Map.of("maxCompletionTokens", true,
                        "reasoningEffort", "high", "helperReasoningEffort", lowest), true), java.time.Duration.ofSeconds(5))) {
                    var service = client.binding().service();
                    service.getChatModel().call(new Prompt("Helper", service.convertOptions(new LlmOptions().withMaxTokens(100).withoutThinking())));
                    assertEquals(lowest, request.get().path("reasoning_effort").asString());
                    assertEquals(100, request.get().path("max_completion_tokens").asInt());
                    assertFalse(request.get().has("max_tokens"));
                    service.getChatModel().call(new Prompt("Answer", service.convertOptions(new LlmOptions().withMaxTokens(200))));
                    assertEquals("high", request.get().path("reasoning_effort").asString());
                    assertEquals(200, request.get().path("max_completion_tokens").asInt());
                }
            }
        } finally { meters.close(); server.stop(0); }
    }

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
