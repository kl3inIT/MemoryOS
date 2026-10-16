package io.memoryos.ai.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.embabel.common.ai.model.LlmOptions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.ai.AiException;
import io.memoryos.ai.ModelBinding;
import io.memoryos.ai.ModelSampling;
import io.memoryos.ai.ModelSettings;
import io.memoryos.ai.ModelTurns;
import io.memoryos.ai.ProviderAdapter;
import io.memoryos.ai.ReasoningEffort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AnthropicProviderAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL_CYCLE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-fixture","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"cache_read_input_tokens":30,"cache_creation_input_tokens":5,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Look it up."}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-1"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"search","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"q\\":\\"leave\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":7,"cache_read_input_tokens":30,"cache_creation_input_tokens":5}}

            event: message_stop
            data: {"type":"message_stop"}

            """;
    private static final String ANSWER = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_2","type":"message","role":"assistant","model":"claude-fixture","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Twelve days."}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":4}}

            event: message_stop
            data: {"type":"message_stop"}

            """;
    private static final ToolCallback SEARCH = new ToolCallback() {
        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("search").description("Search documents.")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}").build();
        }
        @Override public String call(String toolInput) { return "{}"; }
    };

    private record Received(String apiKey, String version, JsonNode body) {}

    @Test
    void aToolCycleStreamsThinkingToTheTurnAndReplaysItWithItsSignature() throws Exception {
        var received = new CopyOnWriteArrayList<Received>();
        var replies = new ArrayList<>(List.of(TOOL_CYCLE, ANSWER));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            received.add(received(exchange));
            respond(exchange, "text/event-stream", replies.remove(0));
        });
        server.start();
        var meters = new SimpleMeterRegistry();
        var adapter = new AnthropicProviderAdapter(ObservationRegistry.NOOP, meters);
        try (var client = adapter.create(connection(server), "claude-fixture", settings(Map.of("reasoningEffort", "medium"), true),
                Duration.ofSeconds(5))) {
            var binding = client.binding();
            var reasoning = new StringBuilder();
            var model = ((ModelTurns) binding.service().getChatModel()).forTurn(new ModelTurns.Turn(new ModelTurns.Listener() {
                @Override public void reasoning(String delta) { reasoning.append(delta); }
            }, false, () -> {}));
            var options = withTools(binding.service().convertOptions(new LlmOptions().withMaxTokens(100)));
            var question = new UserMessage("How many days of leave?");

            var first = model.stream(binding.policy().request(new Prompt(List.<Message>of(question), options), 4096))
                    .collectList().block(Duration.ofSeconds(10));
            var last = first.getLast();
            assertEquals("\n\nLook it up.", reasoning.toString());
            assertEquals(1, first.size(), "Thinking and signature deltas carry no chunk of their own");
            assertEquals("tool_calls", last.getResult().getMetadata().getFinishReason());
            assertEquals("search", last.getResult().getOutput().getToolCalls().getFirst().name());
            // Cached input is part of the input total, so the cached share is a subset of it.
            assertEquals(45, last.getMetadata().getUsage().getPromptTokens());
            assertEquals(7, last.getMetadata().getUsage().getCompletionTokens());
            assertEquals(30L, last.getMetadata().getUsage().getCacheReadInputTokens());

            var sent = received.getFirst();
            assertEquals("fixture-only", sent.apiKey());
            assertEquals("2023-06-01", sent.version());
            assertEquals("claude-fixture", sent.body().path("model").asString());
            assertEquals(100, sent.body().path("max_tokens").asInt());
            assertTrue(sent.body().path("stream").asBoolean());
            assertEquals("adaptive", sent.body().path("thinking").path("type").asString());
            assertEquals("summarized", sent.body().path("thinking").path("display").asString());
            assertEquals("medium", sent.body().path("output_config").path("effort").asString());
            assertEquals("search", sent.body().path("tools").get(0).path("name").asString());
            assertFalse(sent.body().has("temperature"));
            // An answer marks its tools and its conversation for the prompt cache (MEM-269).
            assertEquals("ephemeral", sent.body().path("tools").get(0).path("cache_control").path("type").asString());
            assertEquals("ephemeral", sent.body().path("messages").get(0).path("content").get(0)
                    .path("cache_control").path("type").asString());

            // Embabel carries only the assistant message's metadata across the tool loop.
            var output = last.getResult().getOutput();
            var assistant = AssistantMessage.builder().content("").toolCalls(output.getToolCalls())
                    .properties(output.getMetadata()).build();
            var result = ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("toolu_1", "search", "{\"days\":12}"))).build();
            var second = model.stream(binding.policy().request(new Prompt(List.of(question, assistant, result), options), 4096))
                    .collectList().block(Duration.ofSeconds(10));
            assertEquals("Twelve days.", second.getFirst().getResult().getOutput().getText());
            assertEquals("stop", second.getLast().getResult().getMetadata().getFinishReason());
            assertEquals(12, second.getLast().getMetadata().getUsage().getPromptTokens());

            var replayed = received.get(1).body().path("messages").get(1).path("content");
            assertEquals("thinking", replayed.get(0).path("type").asString());
            assertEquals("Look it up.", replayed.get(0).path("thinking").asString());
            assertEquals("sig-1", replayed.get(0).path("signature").asString());
            assertEquals("tool_use", replayed.get(1).path("type").asString());
            assertEquals("tool_result", received.get(1).body().path("messages").get(2).path("content").get(0).path("type").asString());
        } finally { meters.close(); server.stop(0); }
    }

    @Test
    void levelsHelpersAndForcedToolsEachSendTheThinkingTheMessagesApiAccepts() throws Exception {
        var received = new CopyOnWriteArrayList<Received>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            received.add(received(exchange));
            respond(exchange, "application/json", """
                    {"id":"msg_3","type":"message","role":"assistant","model":"claude-fixture",
                    "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","stop_sequence":null,
                    "usage":{"input_tokens":3,"output_tokens":1}}
                    """);
        });
        server.start();
        var meters = new SimpleMeterRegistry();
        var adapter = new AnthropicProviderAdapter(ObservationRegistry.NOOP, meters);
        try (var adaptive = adapter.create(connection(server), "claude-fixture", settings(Map.of(), true), Duration.ofSeconds(5));
             var budgeted = adapter.create(connection(server), "claude-fixture",
                     settings(Map.of("reasoningEffort", "high", "thinkingBudget", true), true), Duration.ofSeconds(5));
             var plain = adapter.create(connection(server), "claude-fixture", settings(Map.of(), false), Duration.ofSeconds(5))) {
            // A helper call does not think, whatever the model is configured to do.
            var helper = call(budgeted, new LlmOptions().withMaxTokens(100).withoutThinking(), received);
            assertEquals("disabled", helper.path("thinking").path("type").asString());
            assertFalse(helper.toString().contains("cache_control"), "A helper's prompt is not written to the cache");

            // A configured budget model thinks within a budget that stays below the output bound.
            var budget = call(budgeted, new LlmOptions().withMaxTokens(4000), received);
            assertEquals("enabled", budget.path("thinking").path("type").asString());
            assertEquals(2000, budget.path("thinking").path("budget_tokens").asInt());
            assertFalse(budget.has("output_config"));

            // A level pinned on the conversation outranks a model that names none.
            var pinned = adaptive.binding().forOptions(new ModelSampling(0.7, ReasoningEffort.LOW, true), null);
            var low = call(pinned.service().getChatModel(), pinned, new LlmOptions().withMaxTokens(100), received);
            assertEquals("adaptive", low.path("thinking").path("type").asString());
            assertEquals("low", low.path("output_config").path("effort").asString());
            assertFalse(low.has("temperature"), "The Messages API refuses a temperature beside thinking");
            var off = adaptive.binding().forOptions(new ModelSampling(null, ReasoningEffort.OFF, true), null);
            assertEquals("disabled", call(off.service().getChatModel(), off, new LlmOptions().withMaxTokens(100), received)
                    .path("thinking").path("type").asString());

            // A forced tool call cannot think; the last cycle offers no tool at all.
            var service = budgeted.binding().service();
            var tools = withTools(service.convertOptions(new LlmOptions().withMaxTokens(4000)));
            var prompt = new Prompt(List.<Message>of(new UserMessage("Q")), tools);
            service.getChatModel().call(budgeted.binding().policy().request(budgeted.binding().requireTool("search").apply(prompt), 4096));
            assertEquals("any", received.getLast().body().path("tool_choice").path("type").asString());
            assertEquals("disabled", received.getLast().body().path("thinking").path("type").asString());
            service.getChatModel().call(budgeted.binding().policy().request(budgeted.binding().finalRequest().apply(prompt), 4096));
            assertFalse(received.getLast().body().has("tools"));

            // A model that does not reason takes the turn's creativity and no thinking field.
            var creative = plain.binding().forOptions(new ModelSampling(1.6, ReasoningEffort.HIGH, true), null);
            var answer = call(creative.service().getChatModel(), creative, new LlmOptions().withMaxTokens(100), received);
            assertEquals(1.0, answer.path("temperature").asDouble());
            assertFalse(answer.has("thinking"));
            assertFalse(answer.has("output_config"));
        } finally { meters.close(); server.stop(0); }
    }

    @Test
    void optionsOutsideTheMessagesApiAreRefusedBeforeAnyRequest() {
        var meters = new SimpleMeterRegistry();
        var adapter = new AnthropicProviderAdapter(ObservationRegistry.NOOP, meters);
        try {
            adapter.validate("https://api.anthropic.com", "claude-fixture", settings(Map.of("topP", 0.9), false));
            for (Map<String, Object> refused : List.<Map<String, Object>>of(Map.of("frequencyPenalty", 0.1), Map.of("temperature", 1.5),
                    Map.of("reasoningEffort", "minimal"), Map.of("thinkingBudget", "yes")))
                assertThrows(AiException.class, () -> adapter.validate("https://api.anthropic.com", "claude-fixture", settings(refused, true)));
            assertThrows(AiException.class, () -> adapter.validate("https://api.anthropic.com", "claude-fixture",
                    settings(Map.of("reasoningEffort", "low"), false)));
            assertThrows(AiException.class, () -> adapter.validate("https://api.anthropic.com", "claude-fixture",
                    settings(Map.of("temperature", 0.2), true)));
        } finally { meters.close(); }
    }

    @Test
    void theModelsApiIsReadWithTheApiKeyHeaderAndARefusedKeyIsNamed() throws Exception {
        var keys = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            String key = exchange.getRequestHeaders().getFirst("x-api-key");
            keys.add(key + "|" + exchange.getRequestHeaders().getFirst("anthropic-version") + "|"
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            if (!"fixture-only".equals(key)) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            respond(exchange, "application/json", """
                    {"data":[{"type":"model","id":"claude-fixture","display_name":"Fixture","max_input_tokens":200000,
                    "max_tokens":64000,"capabilities":{"image_input":{"supported":true},"thinking":{"supported":true},
                    "structured_outputs":{"supported":false}}},{"type":"model","id":"claude-bare"}],"has_more":false}
                    """);
        });
        server.start();
        var meters = new SimpleMeterRegistry();
        var adapter = new AnthropicProviderAdapter(ObservationRegistry.NOOP, meters);
        try {
            var models = adapter.reportedModels(connection(server), Duration.ofSeconds(5));
            assertEquals("fixture-only|2023-06-01|null", keys.getFirst());
            assertEquals(List.of("claude-fixture", "claude-bare"), models.stream().map(ProviderAdapter.ReportedModel::modelName).toList());
            var fixture = models.getFirst();
            assertEquals(200_000, fixture.contextWindow());
            assertEquals(64_000, fixture.maxOutputTokens());
            assertEquals(true, fixture.vision());
            assertEquals(true, fixture.reasoning());
            assertEquals(false, fixture.structuredOutput());
            assertNull(fixture.toolCalling(), "The Models API publishes no tool flag; the catalog decides");
            assertNull(models.getLast().contextWindow());
            var refused = assertThrows(AiException.class, () -> adapter.reportedModels(
                    new ProviderAdapter.Connection("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", "wrong"), Duration.ofSeconds(5)));
            assertEquals("CHAT_PROVIDER_CREDENTIAL_REJECTED", refused.code());
        } finally { meters.close(); server.stop(0); }
    }

    @Test
    void cancellingAStreamClosesItsConnectionBeforeAndAfterTheResponseStarts() throws Exception {
        var meters = new SimpleMeterRegistry();
        var adapter = new AnthropicProviderAdapter(ObservationRegistry.NOOP, meters);
        // A stop the turn asked for is not a provider failure, so Spring AI must not log one.
        var logs = new ListAppender<ILoggingEvent>();
        var springAi = (Logger) LoggerFactory.getLogger("org.springframework.ai");
        logs.start();
        springAi.addAppender(logs);
        try (var server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             var client = adapter.create(new ProviderAdapter.Connection("http://127.0.0.1:" + server.getLocalPort(), "fixture-only"),
                     "claude-fixture", settings(Map.of(), false), Duration.ofSeconds(30))) {
            server.setSoTimeout(15000);
            var model = client.binding().service().getChatModel();
            var prompt = new Prompt("Question", AnthropicChatOptions.builder().model("claude-fixture").maxTokens(16).build());
            for (boolean started : List.of(false, true)) {
                var ready = new CompletableFuture<Void>();
                var eof = new CompletableFuture<Integer>();
                executor.submit(() -> {
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(15000);
                        var input = socket.getInputStream();
                        var header = new ByteArrayOutputStream();
                        for (int state = 0; state < 4; ) {
                            int value = input.read();
                            if (value < 0) throw new IllegalStateException("Incomplete HTTP request");
                            header.write(value);
                            state = value == "\r\n\r\n".charAt(state) ? state + 1 : value == '\r' ? 1 : 0;
                        }
                        int length = header.toString(StandardCharsets.US_ASCII).lines()
                                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                                .mapToInt(line -> Integer.parseInt(line.substring(line.indexOf(':') + 1).trim())).findFirst().orElseThrow();
                        input.readNBytes(length);
                        if (started) {
                            byte[] bytes = ANSWER.substring(0, ANSWER.indexOf("event: content_block_stop")).getBytes(StandardCharsets.UTF_8);
                            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                                    + "Transfer-Encoding: chunked\r\nConnection: keep-alive\r\n\r\n"
                                    + Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().write(bytes);
                            socket.getOutputStream().write("\r\n".getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                        }
                        ready.complete(null);
                        eof.complete(input.read());
                    } catch (Throwable failure) {
                        ready.completeExceptionally(failure);
                        eof.completeExceptionally(failure);
                    }
                    return null;
                });
                var content = new CompletableFuture<ChatResponse>();
                var subscription = model.stream(prompt).subscribe(content::complete, ignored -> {});
                try {
                    ready.get(10, TimeUnit.SECONDS);
                    if (started) assertEquals("Twelve days.", content.get(10, TimeUnit.SECONDS).getResult().getOutput().getText());
                    subscription.dispose();
                    assertEquals(-1, eof.get(10, TimeUnit.SECONDS));
                } finally { subscription.dispose(); }
            }
            // The reader thread sees the closed connection shortly after the cancel returns.
            Thread.sleep(500);
            assertEquals(List.of(), logs.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                    .map(ILoggingEvent::getFormattedMessage).toList());
        } finally {
            springAi.detachAppender(logs);
            meters.close();
        }
    }

    private static JsonNode call(ProviderAdapter.Client client, LlmOptions options, List<Received> received) {
        return call(client.binding().service().getChatModel(), client.binding(), options, received);
    }

    private static JsonNode call(ChatModel model, ModelBinding binding,
                                 LlmOptions options, List<Received> received) {
        var response = model.call(binding.policy().request(new Prompt("Question", binding.service().convertOptions(options)), 4096));
        assertEquals("stop", response.getResult().getMetadata().getFinishReason());
        return received.getLast().body();
    }

    private static ChatOptions withTools(ChatOptions options) {
        return ((AnthropicChatOptions) options).mutate().toolCallbacks(List.of(SEARCH)).build();
    }

    private static Received received(HttpExchange exchange) throws IOException {
        return new Received(exchange.getRequestHeaders().getFirst("x-api-key"),
                exchange.getRequestHeaders().getFirst("anthropic-version"), JSON.readTree(exchange.getRequestBody().readAllBytes()));
    }

    private static void respond(HttpExchange exchange, String contentType, String content) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static ProviderAdapter.Connection connection(HttpServer server) {
        return new ProviderAdapter.Connection("http://127.0.0.1:" + server.getAddress().getPort(), "fixture-only");
    }

    private static ModelSettings settings(Map<String, Object> options, boolean reasoning) {
        return new ModelSettings(200_000, 8192, new ModelSettings.Capabilities(true, true, false, reasoning, false), options, null,
                "openai-o200k-v1");
    }
}
