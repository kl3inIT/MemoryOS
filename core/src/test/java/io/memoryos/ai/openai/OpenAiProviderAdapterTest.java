package io.memoryos.ai.openai;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.ai.TokenizerProfiles;
import io.memoryos.ai.AiException;
import io.memoryos.ai.ModelTurns;
import io.memoryos.ai.ProviderAdapter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.embabel.agent.spi.loop.StructuredOutputRequest;
import com.embabel.common.ai.model.LlmOptions;
import io.memoryos.ai.ReasoningEffort;
import io.memoryos.ai.ModelSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenAiProviderAdapterTest {
    @Test
    void helperReasoningOverrideSurvivesConversionAndSdkSerializationWithoutChangingAnswerOptions() throws Exception {
        var request = new AtomicReference<JsonNode>();
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body = """
                    {"id":"test","object":"chat.completion","created":1,"model":"configured-model",
                    "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        var meters = new SimpleMeterRegistry();
        var adapter = new OpenAiProviderAdapter(ObservationRegistry.NOOP, meters);
        try {
            var connection = new ProviderAdapter.Connection("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture-only");
            for (String lowest : List.of("default", "none", "minimal", "low")) {
                Map<String, Object> options = lowest.equals("default")
                        ? Map.of("maxCompletionTokens", true, "reasoningEffort", "high")
                        : Map.of("maxCompletionTokens", true, "reasoningEffort", "high", "helperReasoningEffort", lowest);
                try (var client = adapter.create(connection, "configured-model", settings(options, true), Duration.ofSeconds(5))) {
                    var service = client.binding().service();
                    service.getChatModel().call(new Prompt("Helper", service.convertOptions(new LlmOptions().withMaxTokens(100).withoutThinking())));
                    assertEquals(lowest.equals("default") ? "none" : lowest, request.get().path("reasoning_effort").asString());
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
        var adapter = new OpenAiProviderAdapter(ObservationRegistry.NOOP, meters);
        try {
            adapter.validate("http://model.internal/v1", "custom-deployment-name", generic);
            adapter.validate("https://api.example/v1", "reasoning-deployment-name", reasoning);
        } finally { meters.close(); }
        var first = OpenAiProviderAdapter.binding("custom-deployment-name", generic, mock(ChatModel.class), TokenizerProfiles.hostedTokens());
        var options = (OpenAiChatOptions) first.service().convertOptions(new LlmOptions().withMaxTokens(100));
        assertEquals("custom-deployment-name", options.getModel());
        assertEquals(100, options.getMaxTokens());
        assertNull(options.getMaxCompletionTokens());
        assertEquals(0.3, options.getTemperature());
        assertNull(first.service().getPricingModel(), "Unknown pricing must not be reported as free");
        var second = OpenAiProviderAdapter.binding("reasoning-deployment-name", reasoning, mock(ChatModel.class), TokenizerProfiles.hostedTokens());
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
    void aHelperCallKeepsTheTemperatureItAsksForOnlyWhereTheModelTakesOne() {
        var helper = new LlmOptions().withMaxTokens(100).withoutThinking().withTemperature(0.0);
        var plain = OpenAiProviderAdapter.binding("plain", settings(Map.of(), false), mock(ChatModel.class),
                TokenizerProfiles.hostedTokens());
        assertEquals(0.0, ((OpenAiChatOptions) plain.service().convertOptions(helper)).getTemperature());
        // An answer's temperature comes from the configuration and the turn, never from the call.
        assertNull(((OpenAiChatOptions) plain.service().convertOptions(new LlmOptions().withMaxTokens(100).withTemperature(0.9)))
                .getTemperature());
        // A reasoning model, a GPT-5 options family model and a configured temperature keep their own.
        for (var settings : List.of(settings(Map.of(), true), settings(Map.of("maxCompletionTokens", true), false))) {
            var binding = OpenAiProviderAdapter.binding("m", settings, mock(ChatModel.class), TokenizerProfiles.hostedTokens());
            assertNull(((OpenAiChatOptions) binding.service().convertOptions(helper)).getTemperature());
        }
        var configured = OpenAiProviderAdapter.binding("m", settings(Map.of("temperature", 0.3), false), mock(ChatModel.class),
                TokenizerProfiles.hostedTokens());
        assertEquals(0.3, ((OpenAiChatOptions) configured.service().convertOptions(helper)).getTemperature());
    }

    @Test
    void rejectsUnknownOrMalformedOptionsBeforeAnyProviderRequest() {
        var meters = new SimpleMeterRegistry();
        var adapter = new OpenAiProviderAdapter(ObservationRegistry.NOOP, meters);
        try {
            for (var options : List.<Map<String, Object>>of(Map.of("apiKey", "must-not-be-an-option"),
                    Map.of("temperature", "hot"), Map.of("temperature", 3), Map.of("topP", Double.NaN),
                    Map.of("maxCompletionTokens", "true"), Map.of("reasoningEffort", "unlimited"),
                    Map.of("maxCompletionTokens", true, "temperature", 0.5), Map.of("webSearch", "hosted"), Map.of("webSearch", true))) {
                assertThrows(AiException.class, () -> adapter.validate("http://model.internal/v1", "model", settings(options, true)));
            }
        } finally { meters.close(); }
    }
    @Test
    void nativeWebSearchIsAnExplicitToolCapableDeclarationThatSelectsTheResponsesModel() {
        var meters = new SimpleMeterRegistry();
        try {
            var adapter = new OpenAiProviderAdapter(ObservationRegistry.NOOP, meters);
            var declared = settings(Map.of("webSearch", "native"), false);
            var noTools = new ModelSettings(8192, 512, new ModelSettings.Capabilities(true, false, false, false, false), Map.of("webSearch", "native"), null, "openai-o200k-v1");
            assertTrue(adapter.supportsNativeWebSearch(declared));
            assertFalse(adapter.supportsNativeWebSearch(settings(Map.of(), false)), "A GPT-like name alone must not enable hosted search");
            assertFalse(adapter.supportsNativeWebSearch(noTools));
            assertThrows(AiException.class, () -> adapter.validate("http://model.internal/v1", "gpt-5.6", noTools));
            var connection = new ProviderAdapter.Connection("http://127.0.0.1:9/v1", "fixture-only");
            try (var client = adapter.create(connection, "gpt-5.6", declared, Duration.ofSeconds(1))) {
                assertInstanceOf(ModelTurns.class, client.binding().service().getChatModel());
                assertFalse(client.binding().service().getChatModel() instanceof ChatCompletionsReasoning);
            }
            try (var client = adapter.create(connection, "gpt-5.6", settings(Map.of(), false), Duration.ofSeconds(1))) {
                assertInstanceOf(ChatCompletionsReasoning.class, client.binding().service().getChatModel());
            }
        } finally { meters.close(); }
    }

    @Test
    void reasoningSummariesAreAnExplicitReasoningModelDeclarationThatSelectsTheResponsesModel() {
        var meters = new SimpleMeterRegistry();
        try {
            var adapter = new OpenAiProviderAdapter(ObservationRegistry.NOOP, meters);
            assertThrows(AiException.class, () -> adapter.validate("http://model.internal/v1", "gpt-5.6", settings(Map.of("reasoningSummary", "auto"), false)));
            assertThrows(AiException.class, () -> adapter.validate("http://model.internal/v1", "gpt-5.6", settings(Map.of("reasoningSummary", "detailed"), true)));
            var connection = new ProviderAdapter.Connection("http://127.0.0.1:9/v1", "fixture-only");
            try (var client = adapter.create(connection, "gpt-5.6", settings(Map.of("reasoningSummary", "auto"), true), Duration.ofSeconds(1))) {
                var model = assertInstanceOf(ModelTurns.class, client.binding().service().getChatModel());
                assertFalse(model instanceof ChatCompletionsReasoning, "the Responses route");
                assertFalse(model.nativeWebSearch());
            }
        } finally {
            meters.close();
        }
    }

    @Test
    void theOpenAiHostAlwaysSelectsTheResponsesModelAndCompatibleEndpointsDoNot() {
        assertTrue(OpenAiProviderAdapter.servedByOpenAi("https://api.openai.com/v1"));
        assertTrue(OpenAiProviderAdapter.servedByOpenAi("https://API.OPENAI.COM/v1/"));
        assertFalse(OpenAiProviderAdapter.servedByOpenAi("https://openrouter.ai/api/v1"));
        assertFalse(OpenAiProviderAdapter.servedByOpenAi("http://api.openai.com.internal/v1"));
        assertFalse(OpenAiProviderAdapter.servedByOpenAi("not a url"));
        var meters = new SimpleMeterRegistry();
        try {
            var adapter = new OpenAiProviderAdapter(ObservationRegistry.NOOP, meters);
            var openAi = new ProviderAdapter.Connection("https://api.openai.com/v1", "fixture-only");
            try (var client = adapter.create(openAi, "gpt-5.6-terra", settings(Map.of(), true), Duration.ofSeconds(1))) {
                var model = assertInstanceOf(ModelTurns.class, client.binding().service().getChatModel());
                assertFalse(model instanceof ChatCompletionsReasoning, "the Responses route");
                assertFalse(model.nativeWebSearch());
            }
            var gateway = new ProviderAdapter.Connection("https://openrouter.ai/api/v1", "fixture-only");
            try (var client = adapter.create(gateway, "qwen/qwen3.8-27b", settings(Map.of(), true), Duration.ofSeconds(1))) {
                // Chat Completions, which still publishes the reasoning the gateway streams beside the answer.
                assertInstanceOf(ChatCompletionsReasoning.class, client.binding().service().getChatModel());
            }
        } finally { meters.close(); }
    }

    @Test
    void aModelThatDeclaresStructuredOutputSendsTheSchemaAsResponseFormatAndAnyOtherSendsNone() throws Exception {
        var request = new AtomicReference<JsonNode>();
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body = """
                    {"id":"test","object":"chat.completion","created":1,"model":"configured-model",
                    "choices":[{"index":0,"message":{"role":"assistant","content":"{\\"summary\\":\\"ok\\"}"},"finish_reason":"stop"}],
                    "usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        var meters = new SimpleMeterRegistry();
        var adapter = new OpenAiProviderAdapter(ObservationRegistry.NOOP, meters);
        try {
            var connection = new ProviderAdapter.Connection("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "fixture-only");
            try (var client = adapter.create(connection, "configured-model", structured(Map.of(), true), Duration.ofSeconds(5))) {
                var service = client.binding().service();
                assertTrue(client.binding().structuredOutput());
                var options = service.getNativeStructuredOutputConfigurer().configure(
                        service.convertOptions(new LlmOptions().withMaxTokens(100).withoutThinking()),
                        new StructuredOutputRequest("Minutes", SCHEMA, null, true), service.getNativeSupport(), service);
                // What ModelGuard does to a request on its way out: the policy's options and, on the last cycle, the
                // final request without tools. Neither may lose the schema.
                var binding = client.binding();
                service.getChatModel().call(binding.policy().options().apply(
                        binding.finalRequest().apply(new Prompt("Write the minutes.", options))));
                var format = request.get().path("response_format");
                var plain = service.convertOptions(new LlmOptions().withMaxTokens(100));
                assertSame(plain, service.getNativeStructuredOutputConfigurer().configure(plain, null,
                        service.getNativeSupport(), service), "a request without a typed answer is left alone");
                assertEquals("json_schema", format.path("type").asString());
                assertTrue(format.path("json_schema").path("strict").asBoolean());
                assertEquals(mapper.readTree(SCHEMA), format.path("json_schema").path("schema"));
                assertFalse(format.path("json_schema").path("name").asString().isBlank());
                assertEquals(100, request.get().path("max_tokens").asInt(), "the other options survive");
            }
            try (var client = adapter.create(connection, "configured-model", structured(Map.of(), false), Duration.ofSeconds(5))) {
                assertFalse(client.binding().structuredOutput());
                var service = client.binding().service();
                service.getChatModel().call(new Prompt("Write the minutes.", service.convertOptions(new LlmOptions().withMaxTokens(100))));
                assertFalse(request.get().has("response_format"));
            }
        } finally { meters.close(); server.stop(0); }
    }

    @Test
    void theTaskLevelKeepsTheDeclaredStructuredOutput() {
        var meters = new SimpleMeterRegistry();
        try {
            var adapter = new OpenAiProviderAdapter(ObservationRegistry.NOOP, meters);
            var connection = new ProviderAdapter.Connection("http://127.0.0.1:9/v1", "fixture-only");
            try (var client = adapter.create(connection, "gpt-5.6", structured(Map.of(), true), Duration.ofSeconds(1))) {
                for (var effort : ReasoningEffort.values()) {
                    var task = client.binding().forTask(effort);
                    assertTrue(task.structuredOutput(), effort.name());
                    assertInstanceOf(OpenAiStructuredOutput.class, task.service().getNativeStructuredOutputConfigurer());
                    assertTrue(task.withModel(task.service().getChatModel()).getNativeSupport().getStructuredOutput().getSupported());
                }
            }
        } finally { meters.close(); }
    }

    private static final String SCHEMA = """
            {"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"],"additionalProperties":false}""";

    private static ModelSettings structured(Map<String, Object> options, boolean structuredOutput) {
        return new ModelSettings(8192, 512, new ModelSettings.Capabilities(true, true, false, true, structuredOutput), options, null, "openai-o200k-v1");
    }

    private static ModelSettings settings(Map<String, Object> options, boolean reasoning) {
        return new ModelSettings(8192, 512, new ModelSettings.Capabilities(true, true, false, reasoning, false), options, null, "openai-o200k-v1");
    }
}
