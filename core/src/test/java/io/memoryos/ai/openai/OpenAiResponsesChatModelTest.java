package io.memoryos.ai.openai;

import io.memoryos.ai.TurnFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.chat.ChatEvidence;
import io.memoryos.chat.execution.ChatTurnListener;
import io.memoryos.chat.ChatActivityEvent;
import io.memoryos.chat.ChatReasoningDelta;
import io.memoryos.chat.ChatToolEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenAiResponsesChatModelTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private HttpServer server;
    private com.openai.client.OpenAIClientAsync client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body = bodies.get(requests.size() - 1).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        client = OpenAIOkHttpClientAsync.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .apiKey("fixture-only").maxRetries(0).timeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stop() {
        client.close();
        server.stop(0);
        meters.close();
    }

    @Test
    void hostedSearchStreamsTextAndRegistersUrlCitationsWithoutExternalTools() {
        bodies.add(sse(
                event("response.web_search_call.in_progress", Map.of("item_id", "ws_1", "output_index", 0, "sequence_number", 1)),
                event("response.output_item.done", Map.of("output_index", 0, "sequence_number", 2, "item", Map.of("type", "web_search_call",
                        "id", "ws_1", "status", "completed", "action", Map.of("type", "search", "queries", List.of("release notes"))))),
                event("response.output_text.delta", Map.of("item_id", "msg_1", "output_index", 1, "content_index", 0, "delta", "Version 42 ", "sequence_number", 3, "logprobs", List.of())),
                event("response.output_text.delta", Map.of("item_id", "msg_1", "output_index", 1, "content_index", 0, "delta", "was released.", "sequence_number", 4, "logprobs", List.of())),
                event("response.output_item.done", Map.of("output_index", 1, "sequence_number", 5, "item", message("Version 42 was released."))),
                completed(List.of(message("Version 42 was released.")))));
        var evidence = new ChatEvidence();
        var events = new ArrayList<ChatActivityEvent>();
        evidence.publishTo(events::add);
        var model = turnModel(evidence, events, false);

        var responses = model.stream(new Prompt(List.of(new SystemMessage("System"), new UserMessage("What was released?")), options(true))).collectList().block();

        assertEquals("Version 42 was released.", text(responses));
        var last = responses.getLast();
        assertEquals("stop", last.getResult().getMetadata().getFinishReason());
        assertTrue(last.getResult().getOutput().getToolCalls().isEmpty());
        assertEquals(10, last.getMetadata().getUsage().getPromptTokens());
        assertEquals(5, last.getMetadata().getUsage().getCompletionTokens());
        var request = requests.getFirst();
        assertFalse(request.path("store").asBoolean(true));
        assertEquals(List.of("function", "web_search"), types(request.path("tools")));
        assertEquals("search_knowledge", request.path("tools").get(0).path("name").asString());
        assertTrue(request.path("tool_choice").isMissingNode());
        assertEquals("https://example.com/news", evidence.snapshot().getFirst().web().url());
        assertEquals("Release", evidence.snapshot().getFirst().title());
        var tools = events.stream().map(ChatToolEvent.class::cast).toList();
        assertEquals(List.of(ChatToolEvent.Stage.STARTED, ChatToolEvent.Stage.SEARCHING, ChatToolEvent.Stage.COMPLETED, ChatToolEvent.Stage.SOURCE),
                tools.stream().map(ChatToolEvent::stage).toList());
        assertEquals("web_search", tools.getFirst().toolName());
        assertEquals(List.of("release notes"), tools.get(1).search().queries());
        assertTrue(requests.getFirst().path("reasoning").path("summary").isMissingNode());
        assertEquals(1.0, meters.counter("memoryos.chat.native_web_search.calls", "provider", "openai", "status", "completed").count());
    }

    @Test
    void reasoningSummariesStreamAsReasoningWithoutHostedSearch() {
        bodies.add(sse(
                event("response.reasoning_summary_part.added", Map.of("item_id", "rs_1", "output_index", 0, "summary_index", 0, "sequence_number", 1,
                        "part", Map.of("type", "summary_text", "text", ""))),
                event("response.reasoning_summary_text.delta", Map.of("item_id", "rs_1", "output_index", 0, "summary_index", 0,
                        "delta", "Checking the policy.", "sequence_number", 2)),
                // Staging (2026-09-17): a new reasoning item restarts summary_index at 0, and its heading was glued on.
                event("response.reasoning_summary_part.added", Map.of("item_id", "rs_2", "output_index", 0, "summary_index", 0, "sequence_number", 3,
                        "part", Map.of("type", "summary_text", "text", ""))),
                event("response.reasoning_summary_text.delta", Map.of("item_id", "rs_2", "output_index", 0, "summary_index", 0,
                        "delta", "**Answering**", "sequence_number", 4)),
                event("response.output_text.delta", Map.of("item_id", "msg_1", "output_index", 1, "content_index", 0, "delta", "Twelve days.", "sequence_number", 5, "logprobs", List.of())),
                completed(List.of(message("Twelve days.")))));
        var events = new ArrayList<ChatActivityEvent>();
        var responses = new OpenAiResponsesChatModel(mock(ChatModel.class), client, true, false, true, meters);
        assertFalse(responses.nativeWebSearch());
        var model = responses.forTurn(ChatTurnListener.turn(new ChatEvidence(), events::add, false, () -> {}));

        var output = model.stream(new Prompt(List.of(new UserMessage("Leave?")), options(true))).collectList().block();

        assertEquals("Twelve days.", text(output));
        // Each part starts on its own paragraph, including the first, which follows the previous inference's reasoning.
        assertEquals("\n\nChecking the policy.\n\n**Answering**", events.stream().map(event -> ((ChatReasoningDelta) event).text()).reduce("", String::concat));
        var request = requests.getFirst();
        assertEquals("auto", request.path("reasoning").path("summary").asString());
        // No configured effort: Onyx's AUTO, "medium" for OpenAI; GPT-5.1+ would otherwise not reason at all.
        assertEquals("medium", request.path("reasoning").path("effort").asString());
        assertEquals(List.of("function"), types(request.path("tools")));
    }

    @Test
    void anOpenAiServedModelStreamsEveryTurnWithSummariesAndTheRequiredToolChoice() {
        // Staging (2026-09-17): research agents on Chat Completions reasoned silently past the 60 s read gap.
        bodies.add(sse(completed(List.of(message("Planned.")))));
        bodies.add(sse(completed(List.of(message("Done.")))));
        var model = new OpenAiResponsesChatModel(mock(ChatModel.class), client, true, false, true, true, meters)
                .forTurn(ChatTurnListener.turn(new ChatEvidence(), ignored -> {}, false, () -> {}));

        model.stream(new Prompt(List.of(new UserMessage("Research")), options(true).mutate()
                .reasoningEffort("high").toolChoice("required").parallelToolCalls(true).build())).collectList().block();
        model.stream(new Prompt(List.of(new UserMessage("Report")), options(false).mutate().reasoningEffort("none").build())).collectList().block();

        var research = requests.getFirst();
        assertEquals("high", research.path("reasoning").path("effort").asString());
        assertEquals("auto", research.path("reasoning").path("summary").asString());
        assertEquals("required", research.path("tool_choice").asString());
        assertTrue(research.path("parallel_tool_calls").asBoolean(false));
        // Onyx omits reasoning when it is off; a summary would have nothing to summarize.
        var report = requests.get(1);
        assertEquals("none", report.path("reasoning").path("effort").asString());
        assertTrue(report.path("reasoning").path("summary").isMissingNode());
        assertTrue(report.path("tool_choice").isMissingNode());

        // Connection validation streams without a turn and must probe the same route.
        bodies.add(sse(completed(List.of(message("OK.")))));
        new OpenAiResponsesChatModel(mock(ChatModel.class), client, true, false, true, true, meters)
                .stream(new Prompt(List.of(new UserMessage("Reply OK.")), options(true).mutate().toolChoice("none").build())).collectList().block();
        assertEquals("none", requests.get(2).path("tool_choice").asString());
    }

    @Test
    void chatCompletionsToolChoicesMapToTheResponsesShape() {
        assertNull(OpenAiResponsesChatModel.toolChoice(null));
        assertEquals("required", OpenAiResponsesChatModel.toolChoice("required"));
        assertEquals("none", OpenAiResponsesChatModel.toolChoice("none"));
        var named = Map.of("type", "function", "name", "generate_report");
        assertEquals(named, OpenAiResponsesChatModel.toolChoice(Map.of("type", "function", "function", Map.of("name", "generate_report"))));
        assertEquals(named, OpenAiResponsesChatModel.toolChoice("{\"type\":\"function\",\"function\":{\"name\":\"generate_report\"}}"));
        assertThrows(IllegalArgumentException.class, () -> OpenAiResponsesChatModel.toolChoice("sometimes"));
    }

    @Test
    void turnWithoutWebOrSummariesUsesTheChatCompletionsDelegate() {
        var completions = mock(ChatModel.class);
        var prompt = new Prompt("Question", options(true));
        when(completions.stream(prompt)).thenReturn(Flux.empty());
        new OpenAiResponsesChatModel(completions, client, true, true, false, meters)
                .forTurn(ChatTurnListener.turn(new ChatEvidence(), ignored -> {}, false, () -> {}))
                .stream(prompt).collectList().block();
        verify(completions).stream(prompt);
        assertTrue(requests.isEmpty());
    }

    @Test
    void functionCallContinuationEchoesReasoningAndSendsToolOutput() {
        var reasoning = Map.<String, Object>of("type", "reasoning", "id", "rs_1", "summary", List.of(), "encrypted_content", "opaque-state");
        var call = Map.<String, Object>of("type", "function_call", "id", "fc_1", "call_id", "call_1", "name", "search_knowledge", "arguments", "{\"queries\":[\"leave\"]}", "status", "completed");
        bodies.add(sse(completed(List.of(reasoning, call))));
        bodies.add(sse(completed(List.of(message("Twelve days.")))));
        var model = turnModel(new ChatEvidence(), new ArrayList<>(), true);

        var first = model.stream(new Prompt(List.of(new UserMessage("Leave?")), options(true))).collectList().block().getLast();
        var assistant = first.getResult().getOutput();
        assertEquals("tool_calls", first.getResult().getMetadata().getFinishReason());
        assertEquals("call_1", assistant.getToolCalls().getFirst().id());
        assertTrue(assistant.getMetadata().containsKey(OpenAiResponsesChatModel.OUTPUT_ITEMS));
        assertEquals("reasoning.encrypted_content", requests.getFirst().path("include").get(0).asString());

        var toolOutput = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "search_knowledge", "Annual leave is twelve days."))).build();
        model.stream(new Prompt(List.of(new UserMessage("Leave?"), assistant, toolOutput), options(true))).collectList().block();

        var input = requests.get(1).path("input");
        assertEquals(List.of("message", "reasoning", "function_call", "function_call_output"), types(input));
        assertEquals("opaque-state", input.get(1).path("encrypted_content").asString());
        assertEquals("call_1", input.get(3).path("call_id").asString());
        assertEquals("Annual leave is twelve days.", input.get(3).path("output").asString());
    }

    @Test
    void finalCycleWithoutToolCallbacksOmitsHostedSearch() {
        bodies.add(sse(completed(List.of(message("Final.")))));
        var model = turnModel(new ChatEvidence(), new ArrayList<>(), false);

        model.stream(new Prompt(List.of(new UserMessage("Finish")), options(false))).collectList().block();

        assertTrue(requests.getFirst().path("tools").isMissingNode() || requests.getFirst().path("tools").isEmpty());
    }

    @Test
    void failedProviderEventBecomesAnIncompleteResponse() {
        bodies.add(sse(event("response.failed", Map.of("sequence_number", 1, "response", Map.of("id", "resp_1", "output", List.of(),
                "error", Map.of("code", "server_error", "message", "raw provider detail"))))));
        var model = turnModel(new ChatEvidence(), new ArrayList<>(), false);

        var failure = assertThrows(TurnFailureException.class,
                () -> model.stream(new Prompt(List.of(new UserMessage("Hi")), options(true))).collectList().block());

        assertEquals("CHAT_INCOMPLETE_RESPONSE", failure.code());
    }

    @Test
    void anIncompleteResponseEndsLikeOnyxWithItsTextAndWithoutTheCutOffToolCall() {
        var cut = Map.<String, Object>of("type", "function_call", "id", "fc_1", "call_id", "call_1", "name", "search_knowledge",
                "arguments", "{\"queries\":[\"le", "status", "incomplete");
        bodies.add(sse(
                event("response.output_text.delta", Map.of("item_id", "msg_1", "output_index", 0, "content_index", 0,
                        "sequence_number", 1, "delta", "Doanh thu quý 3", "logprobs", List.of())),
                event("response.incomplete", Map.of("sequence_number", 2, "response", Map.of("id", "resp_1", "object", "response",
                        "status", "incomplete", "incomplete_details", Map.of("reason", "max_output_tokens"),
                        "output", List.of(message("Doanh thu quý 3"), cut),
                        "usage", Map.of("input_tokens", 10, "output_tokens", 4096, "total_tokens", 4106,
                                "input_tokens_details", Map.of("cached_tokens", 0), "output_tokens_details", Map.of("reasoning_tokens", 3900)))))));
        var model = turnModel(new ChatEvidence(), new ArrayList<>(), true);

        var responses = model.stream(new Prompt(List.of(new UserMessage("Báo cáo")), options(true))).collectList().block();

        assertEquals("Doanh thu quý 3", text(responses));
        var last = responses.getLast().getResult();
        assertEquals("length", last.getMetadata().getFinishReason());
        assertTrue(last.getOutput().getToolCalls().isEmpty());
    }

    @Test
    void anAnswerCutOffBeforeAnyTextOrCompletedCallReportsTheModelOutputLimit() {
        // Staging 2026-09-19: gpt-5.6-luna declared 4096 output tokens and stopped inside a long run_python call.
        var cut = Map.<String, Object>of("type", "function_call", "id", "fc_1", "call_id", "call_1", "name", "run_python",
                "arguments", "{\"code\":\"import pan", "status", "incomplete");
        bodies.add(sse(event("response.incomplete", Map.of("sequence_number", 1, "response", Map.of("id", "resp_1",
                "object", "response", "status", "incomplete", "incomplete_details", Map.of("reason", "max_output_tokens"),
                "output", List.of(cut))))));
        var model = turnModel(new ChatEvidence(), new ArrayList<>(), true);

        var failure = assertThrows(TurnFailureException.class,
                () -> model.stream(new Prompt(List.of(new UserMessage("Báo cáo")), options(true))).collectList().block());

        assertEquals("CHAT_MODEL_OUTPUT_LIMIT", failure.code());
    }

    @Test
    void turnsWithoutWebAndHelperCallsUseTheChatCompletionsDelegate() {
        var completions = mock(ChatModel.class);
        var prompt = new Prompt("Helper", options(true));
        when(completions.stream(prompt)).thenReturn(Flux.empty());
        var model = new OpenAiResponsesChatModel(completions, client, false, meters);

        model.stream(prompt).collectList().block();
        model.call(prompt);

        verify(completions).stream(prompt);
        verify(completions).call(prompt);
        assertTrue(requests.isEmpty());
    }

    private ChatModel turnModel(ChatEvidence evidence, List<ChatActivityEvent> events, boolean reasoning) {
        return new OpenAiResponsesChatModel(mock(ChatModel.class), client, reasoning, meters)
                .forTurn(ChatTurnListener.turn(evidence, events::add, true, () -> {}));
    }

    private static OpenAiChatOptions options(boolean tools) {
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder().name("search_knowledge").description("Search documents")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"queries\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}").build());
        return OpenAiChatOptions.builder().model("configured-model").maxCompletionTokens(100)
                .toolCallbacks(tools ? List.of(callback) : List.of()).build();
    }

    private static Map<String, Object> message(String text) {
        return Map.of("type", "message", "id", "msg_1", "role", "assistant", "status", "completed", "content", List.of(Map.of(
                "type", "output_text", "text", text, "logprobs", List.of(),
                "annotations", List.of(Map.of("type", "url_citation", "url", "https://example.com/news", "title", "Release", "start_index", 0, "end_index", 10)))));
    }

    private static String completed(List<Map<String, Object>> output) {
        return event("response.completed", Map.of("sequence_number", 99, "response", Map.of("id", "resp_1", "object", "response", "status", "completed",
                "output", output, "usage", Map.of("input_tokens", 10, "output_tokens", 5, "total_tokens", 15,
                        "input_tokens_details", Map.of("cached_tokens", 0), "output_tokens_details", Map.of("reasoning_tokens", 0)))));
    }

    private static String event(String type, Map<String, Object> fields) {
        var payload = new java.util.LinkedHashMap<String, Object>(fields);
        payload.put("type", type);
        return "event: " + type + "\ndata: " + JSON.writeValueAsString(payload) + "\n\n";
    }

    private static String sse(String... events) { return String.join("", events); }

    private static String text(List<ChatResponse> responses) {
        var text = new StringBuilder();
        for (var response : responses) if (response.getResult().getOutput().getText() != null) text.append(response.getResult().getOutput().getText());
        return text.toString();
    }

    private static List<String> types(JsonNode array) {
        var types = new ArrayList<String>();
        array.forEach(node -> types.add(node.path("type").asString()));
        return types;
    }
}
