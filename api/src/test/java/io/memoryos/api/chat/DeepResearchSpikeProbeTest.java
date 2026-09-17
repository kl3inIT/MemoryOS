package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Budget;
import com.embabel.agent.spi.loop.streaming.LlmInferenceStreamEvent;
import com.embabel.chat.AssistantMessageWithToolCalls;
import com.embabel.chat.Message;
import com.embabel.chat.SystemMessage;
import com.embabel.chat.ToolCall;
import com.embabel.chat.ToolResultMessage;
import com.embabel.chat.UserMessage;
import com.embabel.common.ai.model.LlmOptions;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.chat.catalog.ChatProviderAdapter;
import io.memoryos.chat.catalog.ModelSettings;
import io.memoryos.chat.execution.ChatModelBinding;
import io.memoryos.chat.execution.ChatModelGuard;
import io.memoryos.chat.execution.StreamingLlmService;
import io.memoryos.retrieval.SearchTasks;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MEM-101 spike, not a product contract: can MemoryOS drive an Onyx-style orchestrator
 * (one inference per cycle, tool calls returned unexecuted, research agents in parallel)
 * through the existing Embabel streamer and ChatModelGuard?
 * Opt-in only (MEMORYOS_DR_SPIKE=true); live probes additionally need MEMORYOS_DR_SPIKE_LIVE=true.
 */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MEMORYOS_DR_SPIKE", matches = "true")
class DeepResearchSpikeProbeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OpenAiChatProviderAdapter adapter = new OpenAiChatProviderAdapter(ObservationRegistry.NOOP, meters);
    private final AgentProcess process = mock(AgentProcess.class);
    private final Budget budget = mock(Budget.class, RETURNS_DEEP_STUBS);
    private final AtomicInteger toolExecutions = new AtomicInteger();

    @BeforeEach
    void allowInference() {
        when(budget.earlyTerminationPolicy().shouldTerminate(process)).thenReturn(null);
        when(budget.getTokens()).thenReturn(1_000_000);
        when(budget.getCost()).thenReturn(100.0);
    }

    @AfterEach
    void closeMeters() { meters.close(); }

    /** P1+P2+P3: one orchestrator inference per cycle, required tool choice, unexecuted calls, OpenAI history shape. */
    @Test
    void orchestratorCyclesReturnUnexecutedParallelToolCallsThroughTheGuard() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var responses = new ConcurrentLinkedQueue<String>();
        responses.add(content("Planning three tasks. ")
                + toolStart(0, "call_0", "research_agent") + toolArgs(0, "{\"task\":") + toolArgs(0, "\"Revenue 2025\"}")
                + toolStart(1, "call_1", "research_agent") + toolArgs(1, "{\"task\":\"Competitors\"}")
                + toolStart(2, "call_2", "research_agent") + toolArgs(2, "{\"task\":\"Regulation\"}")
                + finish("tool_calls") + usage(50, 20) + "data: [DONE]\n\n");
        responses.add(toolStart(0, "call_9", "generate_report") + toolArgs(0, "{}") + finish("tool_calls") + usage(90, 5) + "data: [DONE]\n\n");
        var server = sseServer(requests, responses);
        try (var client = client(server.getAddress().getPort())) {
            var chunks = new CopyOnWriteArrayList<String>();
            var guard = guard(client.binding(), requiredTools(client.binding().service().getChatModel(), chunks));
            var streamer = new StreamingLlmService(client.binding().withModel(guard)).createMessageStreamer(new LlmOptions().withMaxTokens(1024));

            List<Message> history = new ArrayList<>(List.of(new SystemMessage("You are an orchestrator agent for deep research."),
                    new UserMessage("Assess the company.")));
            var first = streamer.streamInference(history, orchestratorTools()).collectList().block(Duration.ofSeconds(20));
            assertNotNull(first);
            var complete = assertInstanceOf(LlmInferenceStreamEvent.Complete.class, first.getLast());
            var calls = assertInstanceOf(AssistantMessageWithToolCalls.class, complete.getMessage()).getToolCalls();
            System.out.println("SPIKE P1 events=" + first.stream().map(e -> e.getClass().getSimpleName()).toList()
                    + " calls=" + calls.stream().map(c -> c.getName() + c.getArguments()).toList() + " usage=" + complete.getUsage());
            assertEquals(List.of("research_agent", "research_agent", "research_agent"), calls.stream().map(ToolCall::getName).toList());
            assertEquals("{\"task\":\"Revenue 2025\"}", calls.getFirst().getArguments());
            assertInstanceOf(LlmInferenceStreamEvent.Content.class, first.getFirst(), "Pre-tool text streams before completion");
            assertEquals(0, toolExecutions.get(), "The single-inference streamer must not execute tools");
            verify(process, times(1)).recordLlmInvocation(any());

            var body = requests.getFirst();
            System.out.println("SPIKE P2 tool_choice=" + body.path("tool_choice") + " parallel_tool_calls=" + body.path("parallel_tool_calls")
                    + " tools=" + body.path("tools").size() + " stream=" + body.path("stream"));
            assertEquals("required", body.path("tool_choice").asString());
            assertEquals(3, body.path("tools").size());

            history.add(new AssistantMessageWithToolCalls("", calls));
            for (var call : calls) history.add(new ToolResultMessage(call.getId(), call.getName(), "Intermediate report for " + call.getArguments() + " [1]"));
            var second = streamer.streamInference(history, orchestratorTools()).collectList().block(Duration.ofSeconds(20));
            assertNotNull(second);
            var report = assertInstanceOf(AssistantMessageWithToolCalls.class,
                    assertInstanceOf(LlmInferenceStreamEvent.Complete.class, second.getLast()).getMessage());
            assertEquals("generate_report", report.getToolCalls().getFirst().getName());

            var messages = requests.get(1).path("messages");
            var roles = new ArrayList<String>();
            messages.forEach(m -> roles.add(m.path("role").asString()));
            System.out.println("SPIKE P3 roles=" + roles + " assistant=" + find(messages, "assistant"));
            var assistant = find(messages, "assistant");
            assertNotNull(assistant);
            assertEquals(3, assistant.path("tool_calls").size());
            var toolIds = new ArrayList<String>();
            messages.forEach(m -> { if (m.path("role").asString().equals("tool")) toolIds.add(m.path("tool_call_id").asString()); });
            assertEquals(List.of("call_0", "call_1", "call_2"), toolIds);
            assertEquals(0, toolExecutions.get());
            verify(process, times(2)).recordLlmInvocation(any());
            System.out.println("SPIKE P4 raw chunks with tool calls=" + chunks);
        } finally { server.stop(0); }
    }

    /** P5: three research agents in parallel on the product task scope; Stop closes every provider connection. */
    @Test
    void cancelingTheScopeClosesEveryParallelResearchAgentStream() throws Exception {
        try (var server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             var executor = Executors.newVirtualThreadPerTaskExecutor();
             var client = client(server.getLocalPort())) {
            server.setSoTimeout(15000);
            var eofs = new ArrayList<CompletableFuture<Integer>>();
            var ready = new CountDownLatch(3);
            for (int i = 0; i < 3; i++) eofs.add(new CompletableFuture<>());
            executor.submit(() -> {
                for (int i = 0; i < 3; i++) {
                    try {
                        var socket = server.accept();
                        var eof = eofs.get(i);
                        executor.submit(() -> hold(socket, ready, eof));
                    } catch (Exception failure) { eofs.get(i).completeExceptionally(failure); }
                }
            });
            var scope = new SearchTasks.Scope(Duration.ofSeconds(5));
            var received = new CountDownLatch(3);
            var worker = executor.submit(() -> {
                try (var _ = scope.enter()) {
                    return SearchTasks.run(List.of("Revenue", "Competitors", "Regulation").stream().map(task -> (java.util.concurrent.Callable<String>) () -> {
                        var guard = guard(client.binding(), client.binding().service().getChatModel());
                        var streamer = new StreamingLlmService(client.binding().withModel(guard)).createMessageStreamer(new LlmOptions().withMaxTokens(1000));
                        streamer.streamInference(List.of(new UserMessage(task)), List.of()).doOnNext(ignored -> received.countDown())
                                .blockLast(Duration.ofSeconds(60));
                        return task;
                    }).toList(), scope::checkActive);
                }
            });
            assertTrue(ready.await(15, TimeUnit.SECONDS), "Three provider streams must be open concurrently");
            assertTrue(received.await(15, TimeUnit.SECONDS), "Each agent must receive streamed content before Stop");
            long started = System.nanoTime();
            scope.cancel();
            for (var eof : eofs) assertTrue(eof.get(10, TimeUnit.SECONDS) < 0, "Every provider connection closes after Stop");
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> worker.get(10, TimeUnit.SECONDS));
            scope.drained().get(10, TimeUnit.SECONDS);
            System.out.println("SPIKE P5 closedAfterMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    + " workerFailure=" + failure.getCause());
            verify(process, never()).recordLlmInvocation(any());
        }
    }

    /** L1: real OpenAI through the product Chat Completions path, two orchestrator cycles with tool history. */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MEMORYOS_DR_SPIKE_LIVE", matches = "true")
    void liveOrchestratorCyclesThroughProductPath() {
        String key = System.getenv("SPRING_AI_OPENAI_API_KEY");
        assertTrue(key != null && !key.isBlank(), "SPRING_AI_OPENAI_API_KEY is required");
        String model = System.getenv().getOrDefault("MEMORYOS_DR_SPIKE_MODEL", "gpt-5-mini");
        var settings = new ModelSettings(128000, 8192, new ModelSettings.Capabilities(true, true, false, true),
                Map.of("maxCompletionTokens", true, "reasoningEffort", "low"), null, ChatTokenizerProfiles.HOSTED);
        try (var client = adapter.create(new ChatProviderAdapter.Connection("https://api.openai.com/v1", key), model, settings, Duration.ofSeconds(120))) {
            var raw = new CopyOnWriteArrayList<String>();
            var guard = guard(client.binding(), requiredTools(client.binding().service().getChatModel(), raw));
            var streamer = new StreamingLlmService(client.binding().withModel(guard)).createMessageStreamer(new LlmOptions().withMaxTokens(4096));
            List<Message> history = new ArrayList<>(List.of(new SystemMessage("""
                    You are an orchestrator agent for deep research. Conduct research by calling the research_agent tool with high level
                    research tasks. NEVER output normal response tokens, you must only call tools. You have used 1 of 4 max research cycles.
                    The research_agent only receives the task and has no additional context; include all needed context in the task.
                    You are encouraged to call research_agent in parallel when tasks are independent. NEVER call more than 3 in parallel.
                    Call generate_report when all aspects of the plan have been researched.
                    # Research Plan
                    1. Market size of electric two-wheelers in Vietnam
                    2. Main manufacturers and their market share
                    3. Government policy and incentives
                    """), new UserMessage("Research the electric motorbike market in Vietnam.")));
            long started = System.nanoTime();
            var first = streamer.streamInference(history, orchestratorTools()).collectList().block(Duration.ofSeconds(180));
            long firstMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertNotNull(first);
            var message = assertInstanceOf(LlmInferenceStreamEvent.Complete.class, first.getLast()).getMessage();
            var calls = assertInstanceOf(AssistantMessageWithToolCalls.class, message, "tool_choice=required must yield tool calls").getToolCalls();
            System.out.println("SPIKE L1 cycle1 ms=" + firstMs + " model=" + model + " calls=" + calls.stream().map(c -> c.getName() + c.getArguments()).toList()
                    + " contentEvents=" + first.stream().filter(LlmInferenceStreamEvent.Content.class::isInstance).count()
                    + " usage=" + ((LlmInferenceStreamEvent.Complete) first.getLast()).getUsage());
            assertTrue(calls.stream().allMatch(c -> Set.of("research_agent", "generate_report", "think_tool").contains(c.getName())));
            assertEquals(0, toolExecutions.get());

            history.add(new AssistantMessageWithToolCalls("", calls));
            for (var call : calls) history.add(new ToolResultMessage(call.getId(), call.getName(),
                    "Intermediate report: Vietnam sold about 1.2 million electric two-wheelers in 2025 [1]; VinFast leads with a majority share [2]."));
            started = System.nanoTime();
            var second = streamer.streamInference(history, orchestratorTools()).collectList().block(Duration.ofSeconds(180));
            assertNotNull(second);
            var next = assertInstanceOf(AssistantMessageWithToolCalls.class,
                    assertInstanceOf(LlmInferenceStreamEvent.Complete.class, second.getLast()).getMessage()).getToolCalls();
            System.out.println("SPIKE L1 cycle2 ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    + " calls=" + next.stream().map(c -> c.getName() + c.getArguments()).toList());
            assertEquals(0, toolExecutions.get());
            verify(process, times(2)).recordLlmInvocation(any());
        }
    }

    /** L2: can think_tool arguments stream incrementally? Responses SDK events, non-reasoning model as in Onyx. */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MEMORYOS_DR_SPIKE_LIVE", matches = "true")
    void liveThinkToolArgumentsStreamAsDeltas() {
        String key = System.getenv("SPRING_AI_OPENAI_API_KEY");
        assertTrue(key != null && !key.isBlank(), "SPRING_AI_OPENAI_API_KEY is required");
        String model = System.getenv().getOrDefault("MEMORYOS_DR_SPIKE_THINK_MODEL", "gpt-4.1-mini");
        var sdk = com.openai.client.okhttp.OpenAIOkHttpClient.builder().apiKey(key).maxRetries(0).timeout(Duration.ofSeconds(120)).build();
        try {
            var parameters = com.openai.models.responses.FunctionTool.Parameters.builder()
                    .putAdditionalProperty("type", com.openai.core.JsonValue.from("object"))
                    .putAdditionalProperty("properties", com.openai.core.JsonValue.from(Map.of("reasoning", Map.of("type", "string"))))
                    .putAdditionalProperty("required", com.openai.core.JsonValue.from(List.of("reasoning"))).build();
            var params = com.openai.models.responses.ResponseCreateParams.builder().model(model).store(false)
                    .instructions("You are a deep research orchestrator. Before delegating any research, call think_tool with a detailed "
                            + "chain of thought of at least 150 words in paragraph form about how to approach the question. Only call tools.")
                    .input("Research the electric motorbike market in Vietnam.")
                    .addTool(com.openai.models.responses.FunctionTool.builder().name("think_tool")
                            .description("Reason between research steps.").parameters(parameters).strict(false).build())
                    .toolChoice(com.openai.models.responses.ToolChoiceOptions.REQUIRED).maxOutputTokens(1024).build();
            var deltas = new ArrayList<String>();
            var arrivals = new ArrayList<Long>();
            String done = null;
            long started = System.nanoTime();
            try (var stream = sdk.responses().createStreaming(params)) {
                for (var event : (Iterable<com.openai.models.responses.ResponseStreamEvent>) stream.stream()::iterator) {
                    if (event.functionCallArgumentsDelta().isPresent()) {
                        deltas.add(event.functionCallArgumentsDelta().get().delta());
                        arrivals.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    }
                    if (event.functionCallArgumentsDone().isPresent()) done = event.functionCallArgumentsDone().get().arguments();
                }
            }
            System.out.println("SPIKE L2 model=" + model + " deltas=" + deltas.size() + " firstMs=" + (arrivals.isEmpty() ? -1 : arrivals.getFirst())
                    + " lastMs=" + (arrivals.isEmpty() ? -1 : arrivals.getLast()) + " first5=" + deltas.stream().limit(5).toList()
                    + " doneChars=" + (done == null ? -1 : done.length()));
            assertNotNull(done, "think_tool must be called");
            assertTrue(deltas.size() > 10, "Arguments must arrive as many incremental deltas");
            assertEquals(done, String.join("", deltas));
        } finally { sdk.close(); }
    }

    /**
     * P6, research agent loop decision: one streamInference per cycle through the guard with direct Tool.call,
     * a per-cycle system prompt, only the first tool type of a batch executed, generate_report as a loop signal,
     * and the intermediate report as a tool-free inference over the kept history. The Embabel PromptRunner loop
     * cannot express the last three (it executes every call, returns only text and exposes no history).
     */
    @Test
    void researchAgentLoopRunsDirectToolCallsAndWritesTheIntermediateReport() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var responses = new ConcurrentLinkedQueue<String>();
        responses.add(toolStart(0, "call_0", "internal_search") + toolArgs(0, "{\"query\":\"revenue\"}")
                + toolStart(1, "call_1", "web_search") + toolArgs(1, "{\"query\":\"revenue news\"}")
                + finish("tool_calls") + usage(40, 10) + "data: [DONE]\n\n");
        responses.add(toolStart(0, "call_2", "generate_report") + toolArgs(0, "{}") + finish("tool_calls") + usage(60, 5) + "data: [DONE]\n\n");
        responses.add(content("Revenue grew [1].") + finish("stop") + usage(80, 12) + "data: [DONE]\n\n");
        var server = sseServer(requests, responses);
        var executed = new CopyOnWriteArrayList<String>();
        Tool.Function search = input -> { executed.add("internal_search:" + input); return Tool.Result.text("[1] Revenue grew 12%"); };
        Tool.Function web = input -> { executed.add("web_search:" + input); return Tool.Result.text("unused"); };
        Tool.Function report = input -> { throw new AssertionError("generate_report is a loop signal, never executed"); };
        var internalSearch = Tool.Companion.of("internal_search", "Search internal documents.",
                Tool.InputSchema.of(Tool.Parameter.string("query", "Query")), Tool.Metadata.DEFAULT, search);
        var webSearch = Tool.Companion.of("web_search", "Search the public web.",
                Tool.InputSchema.of(Tool.Parameter.string("query", "Query")), Tool.Metadata.DEFAULT, web);
        var generateReport = Tool.Companion.of("generate_report", "Write the intermediate report.", Tool.InputSchema.empty(), Tool.Metadata.DEFAULT, report);
        var tools = List.of(internalSearch, webSearch, generateReport);
        var byName = Map.of("internal_search", internalSearch, "web_search", webSearch, "generate_report", generateReport);
        try (var client = client(server.getAddress().getPort())) {
            var chunks = new CopyOnWriteArrayList<String>();
            // Research guards use one extra cycle and an identity final request: the loop, not the guard, forces the report.
            var guard = new ChatModelGuard(requiredTools(client.binding().service().getChatModel(), chunks), process,
                    client.binding().service(), budget, 8 + 1, () -> {}, client.binding().policy(), 12000, prompt -> prompt);
            guard.outputLimit(1000);
            var streamer = new StreamingLlmService(client.binding().withModel(guard)).createMessageStreamer(new LlmOptions().withMaxTokens(1000));
            List<Message> history = new ArrayList<>(List.of(new UserMessage("Research: revenue in 2025")));
            boolean reportRequested = false;
            for (int cycle = 1; cycle <= 8 && !reportRequested; cycle++) {
                var request = new ArrayList<Message>();
                request.add(new SystemMessage("You are a research agent. You are on cycle " + cycle + " of 8."));
                request.addAll(history);
                var events = streamer.streamInference(request, tools).collectList().block(Duration.ofSeconds(20));
                assertNotNull(events);
                var message = assertInstanceOf(LlmInferenceStreamEvent.Complete.class, events.getLast()).getMessage();
                var calls = assertInstanceOf(AssistantMessageWithToolCalls.class, message).getToolCalls();
                String first = calls.getFirst().getName();
                var kept = calls.stream().filter(call -> call.getName().equals(first)).toList();
                if (first.equals("generate_report")) { reportRequested = true; continue; }
                history.add(new AssistantMessageWithToolCalls("", kept));
                for (var call : kept) {
                    var result = assertInstanceOf(Tool.Result.Text.class, byName.get(call.getName()).call(call.getArguments()));
                    history.add(new ToolResultMessage(call.getId(), call.getName(), result.getContent()));
                }
            }
            assertTrue(reportRequested);
            var reportRequest = new ArrayList<Message>(List.of(new SystemMessage("Write the intermediate report and keep citation markers.")));
            reportRequest.addAll(history);
            var text = streamer.streamInference(reportRequest, List.of())
                    .filter(LlmInferenceStreamEvent.Content.class::isInstance)
                    .map(event -> ((LlmInferenceStreamEvent.Content) event).getText()).collectList().block(Duration.ofSeconds(20));
            assertNotNull(text);
            System.out.println("SPIKE P6 executed=" + executed + " requests=" + requests.size() + " report=" + String.join("", text));
            assertEquals("Revenue grew [1].", String.join("", text));
            assertEquals(List.of("internal_search:{\"query\":\"revenue\"}"), executed, "Only the first tool type of a batch runs");
            assertEquals("required", requests.get(0).path("tool_choice").asString());
            assertTrue(requests.get(0).path("messages").toString().contains("cycle 1 of 8"));
            assertTrue(requests.get(1).path("messages").toString().contains("cycle 2 of 8"));
            assertEquals(1, find(requests.get(1).path("messages"), "assistant").path("tool_calls").size());
            assertTrue(requests.get(2).path("tool_choice").isMissingNode(), "The report inference has no tools");
            assertEquals(0, requests.get(2).path("tools").size());
            verify(process, times(3)).recordLlmInvocation(any());
        } finally { server.stop(0); }
    }

    private List<Tool> orchestratorTools() {
        Tool.Function refuse = input -> { toolExecutions.incrementAndGet(); return Tool.Result.text("must not run"); };
        return List.of(
                Tool.Companion.of("research_agent", "Conduct research on a specific topic.",
                        Tool.InputSchema.of(Tool.Parameter.string("task", "The research task")), Tool.Metadata.DEFAULT, refuse),
                Tool.Companion.of("generate_report", "Generate the final research report.", Tool.InputSchema.empty(), Tool.Metadata.DEFAULT, refuse),
                Tool.Companion.of("think_tool", "Reason between research steps.",
                        Tool.InputSchema.of(Tool.Parameter.string("reasoning", "Chain of thought")), Tool.Metadata.DEFAULT, refuse));
    }

    private ChatModelGuard guard(ChatModelBinding binding, ChatModel delegate) {
        var guard = new ChatModelGuard(delegate, process, binding.service(), budget, 8, () -> {}, binding.policy(), 12000, binding.finalRequest());
        guard.outputLimit(1024);
        return guard;
    }

    /** Onyx sets tool_choice=REQUIRED for orchestrator and agent cycles; also records raw provider chunks. */
    private static ChatModel requiredTools(ChatModel delegate, List<String> chunks) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) { return delegate.call(prompt); }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                var options = assertInstanceOf(OpenAiChatOptions.class, prompt.getOptions());
                var request = options.getToolCallbacks().isEmpty() ? prompt
                        : new Prompt(prompt.getInstructions(), options.mutate().toolChoice("required").build());
                return delegate.stream(request).doOnNext(response -> {
                    if (response.getResult() != null && !response.getResult().getOutput().getToolCalls().isEmpty())
                        chunks.add(response.getResult().getOutput().getToolCalls().stream()
                                .map(call -> call.name() + ":" + call.arguments()).toList().toString());
                });
            }
        };
    }

    private ChatProviderAdapter.Client client(int port) {
        return adapter.create(new ChatProviderAdapter.Connection("http://127.0.0.1:" + port + "/v1", "fixture-key"), "fixture",
                new ModelSettings(16000, 1024, new ModelSettings.Capabilities(true, true, false, false), Map.of(), null, ChatTokenizerProfiles.HOSTED),
                Duration.ofSeconds(30));
    }

    private static HttpServer sseServer(List<JsonNode> requests, ConcurrentLinkedQueue<String> responses) throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] body = responses.remove().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        return server;
    }

    private static void hold(java.net.Socket socket, CountDownLatch ready, CompletableFuture<Integer> eof) {
        try (socket) {
            socket.setSoTimeout(30000);
            var input = socket.getInputStream();
            var header = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4) {
                int value = input.read();
                if (value < 0) throw new IllegalStateException("Incomplete HTTP request");
                header.write(value);
                state = value == "\r\n\r\n".charAt(state) ? state + 1 : value == '\r' ? 1 : 0;
            }
            int length = header.toString(StandardCharsets.US_ASCII).lines()
                    .filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                    .mapToInt(line -> Integer.parseInt(line.substring(line.indexOf(':') + 1).trim())).findFirst().orElseThrow();
            input.readNBytes(length);
            byte[] data = content("Searching ").getBytes(StandardCharsets.UTF_8);
            var output = socket.getOutputStream();
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + Integer.toHexString(data.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(data);
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
            ready.countDown();
            eof.complete(input.read());
        } catch (java.net.SocketException closed) {
            eof.complete(-2);
        } catch (Throwable failure) {
            eof.completeExceptionally(failure);
        }
    }

    private static JsonNode find(JsonNode messages, String role) {
        for (var message : messages) if (message.path("role").asString().equals(role)) return message;
        return null;
    }

    private static String content(String text) { return chunk(Map.of("content", text), null); }

    private static String toolStart(int index, String id, String name) {
        return chunk(Map.of("tool_calls", List.of(Map.of("index", index, "id", id, "type", "function",
                "function", Map.of("name", name, "arguments", "")))), null);
    }

    private static String toolArgs(int index, String fragment) {
        return chunk(Map.of("tool_calls", List.of(Map.of("index", index, "function", Map.of("arguments", fragment)))), null);
    }

    private static String finish(String reason) { return chunk(Map.of(), reason); }

    private static String chunk(Map<String, Object> delta, String finishReason) {
        var choice = new LinkedHashMap<String, Object>();
        choice.put("index", 0);
        choice.put("delta", delta);
        if (finishReason != null) choice.put("finish_reason", finishReason);
        return "data: " + JSON.writeValueAsString(Map.of("id", "fixture", "object", "chat.completion.chunk", "created", 1,
                "model", "fixture", "choices", List.of(choice))) + "\n\n";
    }

    private static String usage(int input, int output) {
        return "data: " + JSON.writeValueAsString(Map.of("id", "fixture", "object", "chat.completion.chunk", "created", 1,
                "model", "fixture", "choices", List.of(), "usage", Map.of("prompt_tokens", input, "completion_tokens", output,
                        "total_tokens", input + output))) + "\n\n";
    }
}
