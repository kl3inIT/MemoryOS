package io.memoryos.chat.catalog.openai;

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
        var adapter = new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, meters);
        try {
            var connection = new io.memoryos.chat.catalog.ChatProviderAdapter.Connection("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture-only");
            for (String lowest : java.util.List.of("default", "none", "minimal", "low")) {
                Map<String, Object> options = lowest.equals("default")
                        ? Map.of("maxCompletionTokens", true, "reasoningEffort", "high")
                        : Map.of("maxCompletionTokens", true, "reasoningEffort", "high", "helperReasoningEffort", lowest);
                try (var client = adapter.create(connection, "configured-model", settings(options, true), java.time.Duration.ofSeconds(5))) {
                    var service = client.binding().service();
                    service.getChatModel().call(new Prompt("Helper", service.convertOptions(new LlmOptions().withMaxTokens(100).withoutThinking())));
                    assertEquals(lowest.equals("default") ? "minimal" : lowest, request.get().path("reasoning_effort").asString());
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
        var adapter = new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, meters);
        try {
            adapter.validate("http://model.internal/v1", "custom-deployment-name", generic);
            adapter.validate("https://api.example/v1", "reasoning-deployment-name", reasoning);
        } finally { meters.close(); }
        var first = OpenAiChatProviderAdapter.binding("custom-deployment-name", generic, mock(ChatModel.class), ChatTokenizerProfiles.hostedTokens());
        var options = (OpenAiChatOptions) first.service().convertOptions(new LlmOptions().withMaxTokens(100));
        assertEquals("custom-deployment-name", options.getModel());
        assertEquals(100, options.getMaxTokens());
        assertNull(options.getMaxCompletionTokens());
        assertEquals(0.3, options.getTemperature());
        assertNull(first.service().getPricingModel(), "Unknown pricing must not be reported as free");
        var second = OpenAiChatProviderAdapter.binding("reasoning-deployment-name", reasoning, mock(ChatModel.class), ChatTokenizerProfiles.hostedTokens());
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
        var adapter = new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, meters);
        try {
            for (var options : java.util.List.<Map<String, Object>>of(Map.of("apiKey", "must-not-be-an-option"),
                    Map.of("temperature", "hot"), Map.of("temperature", 3), Map.of("topP", Double.NaN),
                    Map.of("maxCompletionTokens", "true"), Map.of("reasoningEffort", "unlimited"),
                    Map.of("maxCompletionTokens", true, "temperature", 0.5), Map.of("webSearch", "hosted"), Map.of("webSearch", true))) {
                assertThrows(ChatException.class, () -> adapter.validate("http://model.internal/v1", "model", settings(options, true)));
            }
        } finally { meters.close(); }
    }
    @Test
    void nativeWebSearchIsAnExplicitToolCapableDeclarationThatSelectsTheResponsesModel() {
        var meters = new SimpleMeterRegistry();
        try {
            var adapter = new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, meters);
            var declared = settings(Map.of("webSearch", "native"), false);
            var noTools = new ModelSettings(8192, 512, new ModelSettings.Capabilities(true, false, false, false), Map.of("webSearch", "native"), null, "openai-o200k-v1");
            assertTrue(adapter.supportsNativeWebSearch(declared));
            assertFalse(adapter.supportsNativeWebSearch(settings(Map.of(), false)), "A GPT-like name alone must not enable hosted search");
            assertFalse(adapter.supportsNativeWebSearch(noTools));
            assertThrows(ChatException.class, () -> adapter.validate("http://model.internal/v1", "gpt-5.6", noTools));
            var connection = new io.memoryos.chat.catalog.ChatProviderAdapter.Connection("http://127.0.0.1:9/v1", "fixture-only");
            try (var client = adapter.create(connection, "gpt-5.6", declared, java.time.Duration.ofSeconds(1))) {
                assertInstanceOf(io.memoryos.chat.execution.ChatModelTurns.class, client.binding().service().getChatModel());
                assertFalse(client.binding().service().getChatModel() instanceof ChatCompletionsReasoning);
            }
            try (var client = adapter.create(connection, "gpt-5.6", settings(Map.of(), false), java.time.Duration.ofSeconds(1))) {
                assertInstanceOf(ChatCompletionsReasoning.class, client.binding().service().getChatModel());
            }
        } finally { meters.close(); }
    }

    @Test
    void reasoningSummariesAreAnExplicitReasoningModelDeclarationThatSelectsTheResponsesModel() {
        var meters = new SimpleMeterRegistry();
        try {
            var adapter = new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, meters);
            assertThrows(ChatException.class, () -> adapter.validate("http://model.internal/v1", "gpt-5.6", settings(Map.of("reasoningSummary", "auto"), false)));
            assertThrows(ChatException.class, () -> adapter.validate("http://model.internal/v1", "gpt-5.6", settings(Map.of("reasoningSummary", "detailed"), true)));
            var connection = new io.memoryos.chat.catalog.ChatProviderAdapter.Connection("http://127.0.0.1:9/v1", "fixture-only");
            try (var client = adapter.create(connection, "gpt-5.6", settings(Map.of("reasoningSummary", "auto"), true), java.time.Duration.ofSeconds(1))) {
                var model = assertInstanceOf(io.memoryos.chat.execution.ChatModelTurns.class, client.binding().service().getChatModel());
                assertFalse(model instanceof ChatCompletionsReasoning, "the Responses route");
                assertFalse(model.nativeWebSearch());
            }
        } finally {
            meters.close();
        }
    }

    @Test
    void theOpenAiHostAlwaysSelectsTheResponsesModelAndCompatibleEndpointsDoNot() {
        assertTrue(OpenAiChatProviderAdapter.servedByOpenAi("https://api.openai.com/v1"));
        assertTrue(OpenAiChatProviderAdapter.servedByOpenAi("https://API.OPENAI.COM/v1/"));
        assertFalse(OpenAiChatProviderAdapter.servedByOpenAi("https://openrouter.ai/api/v1"));
        assertFalse(OpenAiChatProviderAdapter.servedByOpenAi("http://api.openai.com.internal/v1"));
        assertFalse(OpenAiChatProviderAdapter.servedByOpenAi("not a url"));
        var meters = new SimpleMeterRegistry();
        try {
            var adapter = new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, meters);
            var openAi = new io.memoryos.chat.catalog.ChatProviderAdapter.Connection("https://api.openai.com/v1", "fixture-only");
            try (var client = adapter.create(openAi, "gpt-5.6-terra", settings(Map.of(), true), java.time.Duration.ofSeconds(1))) {
                var model = assertInstanceOf(io.memoryos.chat.execution.ChatModelTurns.class, client.binding().service().getChatModel());
                assertFalse(model instanceof ChatCompletionsReasoning, "the Responses route");
                assertFalse(model.nativeWebSearch());
            }
            var gateway = new io.memoryos.chat.catalog.ChatProviderAdapter.Connection("https://openrouter.ai/api/v1", "fixture-only");
            try (var client = adapter.create(gateway, "qwen/qwen3.8-27b", settings(Map.of(), true), java.time.Duration.ofSeconds(1))) {
                // Chat Completions, which still publishes the reasoning the gateway streams beside the answer.
                assertInstanceOf(ChatCompletionsReasoning.class, client.binding().service().getChatModel());
            }
        } finally { meters.close(); }
    }

    private static ModelSettings settings(Map<String, Object> options, boolean reasoning) {
        return new ModelSettings(8192, 512, new ModelSettings.Capabilities(true, true, false, reasoning), options, null, "openai-o200k-v1");
    }
}
