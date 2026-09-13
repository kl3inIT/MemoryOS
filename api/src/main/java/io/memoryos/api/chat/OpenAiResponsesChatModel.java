package io.memoryos.api.chat;

import com.openai.client.OpenAIClientAsync;
import com.openai.core.JsonValue;
import com.openai.core.ObjectMappers;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionWebSearch;
import com.openai.models.responses.ResponseIncludable;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.Tool;
import io.memoryos.chat.ChatSearchEvent;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.execution.NativeWebSearch;
import io.memoryos.retrieval.SearchFilters;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * OpenAI Responses API with hosted {@code web_search}, used only for a Chat turn that requested Web.
 * Helpers and turns without Web keep the Chat Completions delegate. Requests are stateless
 * ({@code store=false}); output items needed by the next tool cycle ride in assistant message properties.
 */
@org.jspecify.annotations.NullMarked
final class OpenAiResponsesChatModel implements ChatModel, NativeWebSearch {
    static final String OUTPUT_ITEMS = "memoryos.openai.responses.output";
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private final ChatModel completions;
    private final OpenAIClientAsync client;
    private final boolean reasoning;
    private final MeterRegistry meters;
    private final @Nullable Turn turn;
    private final AtomicBoolean firstRequest = new AtomicBoolean(true);

    OpenAiResponsesChatModel(ChatModel completions, OpenAIClientAsync client, boolean reasoning, MeterRegistry meters) {
        this(completions, client, reasoning, meters, null);
    }

    private OpenAiResponsesChatModel(ChatModel completions, OpenAIClientAsync client, boolean reasoning, MeterRegistry meters, @Nullable Turn turn) {
        this.completions = completions;
        this.client = client;
        this.reasoning = reasoning;
        this.meters = meters;
        this.turn = turn;
    }

    @Override public ChatModel forTurn(Turn value) { return new OpenAiResponsesChatModel(completions, client, reasoning, meters, value); }

    /** Synchronous helpers (selection, classification) never search the Web. */
    @Override public ChatResponse call(Prompt prompt) { return completions.call(prompt); }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        var active = turn;
        if (active == null) return completions.stream(prompt);
        if (!(prompt.getOptions() instanceof OpenAiChatOptions options)) return Flux.error(new IllegalArgumentException("CHAT_UNSUPPORTED_OPTIONS"));
        // The final-cycle policy removes every tool callback; hosted search is a tool as well.
        boolean tools = options.getToolCallbacks() != null && !options.getToolCallbacks().isEmpty();
        boolean required = active.required() && firstRequest.getAndSet(false);
        ResponseCreateParams params;
        try { params = request(prompt, options, tools, required); }
        catch (RuntimeException invalid) { return Flux.error(new IllegalArgumentException("CHAT_UNSUPPORTED_OPTIONS")); }
        return Flux.create(sink -> {
            var state = new StreamState(active, sink, required);
            AsyncStreamResponse<ResponseStreamEvent> stream = client.responses().createStreaming(params);
            sink.onDispose(stream::close);
            stream.subscribe(new AsyncStreamResponse.Handler<>() {
                @Override public void onNext(ResponseStreamEvent event) {
                    try { state.accept(event); } catch (RuntimeException failure) { sink.error(failure); }
                }
                @Override public void onComplete(Optional<Throwable> error) {
                    if (error.isPresent()) sink.error(new IllegalStateException("CHAT_PROVIDER_UNAVAILABLE"));
                    else if (!state.finished) sink.error(new IllegalStateException("CHAT_INCOMPLETE_RESPONSE"));
                }
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private ResponseCreateParams request(Prompt prompt, OpenAiChatOptions options, boolean tools, boolean required) {
        var mapper = ObjectMappers.jsonMapper();
        var input = new ArrayList<ResponseInputItem>();
        for (var message : prompt.getInstructions())
            for (var item : input(message)) input.add(mapper.convertValue(item, ResponseInputItem.class));
        var builder = ResponseCreateParams.builder().model(options.getModel()).store(false)
                .input(ResponseCreateParams.Input.ofResponse(input));
        Integer maxOutput = options.getMaxCompletionTokens() != null ? options.getMaxCompletionTokens() : options.getMaxTokens();
        if (maxOutput != null) builder.maxOutputTokens(maxOutput.longValue());
        if (options.getTemperature() != null) builder.temperature(options.getTemperature());
        if (options.getTopP() != null) builder.topP(options.getTopP());
        if (options.getReasoningEffort() != null)
            builder.putAdditionalBodyProperty("reasoning", JsonValue.from(Map.of("effort", options.getReasoningEffort())));
        if (reasoning) builder.include(List.of(ResponseIncludable.REASONING_ENCRYPTED_CONTENT));
        if (tools) {
            var declared = new ArrayList<Tool>();
            for (var callback : java.util.Objects.requireNonNullElse(options.getToolCallbacks(), List.<org.springframework.ai.tool.ToolCallback>of())) {
                var definition = callback.getToolDefinition();
                var function = new LinkedHashMap<String, Object>();
                function.put("type", "function");
                function.put("name", definition.name());
                function.put("description", definition.description());
                function.put("parameters", JSON.readValue(definition.inputSchema(), Map.class));
                function.put("strict", false);
                declared.add(mapper.convertValue(function, Tool.class));
            }
            declared.add(mapper.convertValue(Map.of("type", "web_search"), Tool.class));
            builder.tools(declared);
            if (required) builder.toolChoice(mapper.convertValue(Map.of("type", "web_search"), ResponseCreateParams.ToolChoice.class));
        }
        return builder.build();
    }

    private static List<Map<String, Object>> input(Message message) {
        var items = new ArrayList<Map<String, Object>>();
        switch (message) {
            case SystemMessage system -> items.add(Map.of("type", "message", "role", "system", "content", text(system.getText())));
            case UserMessage user -> {
                var content = new ArrayList<Map<String, Object>>();
                content.add(Map.of("type", "input_text", "text", text(user.getText())));
                for (var media : user.getMedia())
                    content.add(Map.of("type", "input_image", "detail", "auto", "image_url",
                            "data:" + media.getMimeType() + ";base64," + Base64.getEncoder().encodeToString(media.getDataAsByteArray())));
                items.add(Map.of("type", "message", "role", "user", "content", content));
            }
            case AssistantMessage assistant -> {
                Object echoed = assistant.getMetadata().get(OUTPUT_ITEMS);
                if (echoed instanceof String json) {
                    // Exact prior output (reasoning, hosted search, function calls) preserves continuation.
                    for (Object item : JSON.readValue(json, List.class)) {
                        @SuppressWarnings("unchecked") var map = (Map<String, Object>) item;
                        items.add(map);
                    }
                } else {
                    if (assistant.getText() != null && !assistant.getText().isEmpty())
                        items.add(Map.of("type", "message", "role", "assistant", "content", assistant.getText()));
                    for (var call : assistant.getToolCalls())
                        items.add(Map.of("type", "function_call", "call_id", call.id(), "name", call.name(), "arguments", call.arguments()));
                }
            }
            case ToolResponseMessage tool -> {
                for (var response : tool.getResponses())
                    items.add(Map.of("type", "function_call_output", "call_id", response.id(), "output", text(response.responseData())));
            }
            default -> throw new IllegalArgumentException("Unsupported message type");
        }
        return items;
    }

    private static String text(@Nullable String value) { return value == null ? "" : value; }

    private final class StreamState {
        private final Turn turn;
        private final FluxSink<ChatResponse> sink;
        private final boolean required;
        private final Set<String> started = new HashSet<>();
        private @Nullable String lastSearch;
        private boolean searched;
        private boolean finished;

        StreamState(Turn turn, FluxSink<ChatResponse> sink, boolean required) {
            this.turn = turn;
            this.sink = sink;
            this.required = required;
        }

        void accept(ResponseStreamEvent event) {
            turn.checkActive().run();
            event.outputTextDelta().ifPresent(delta -> {
                if (!delta.delta().isEmpty()) sink.next(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(delta.delta()).build()))));
            });
            event.webSearchCallInProgress().ifPresent(progress -> start(progress.itemId()));
            event.webSearchCallSearching().ifPresent(progress -> start(progress.itemId()));
            event.outputItemDone().ifPresent(done -> done(done.item()));
            event.completed().ifPresent(completed -> complete(completed.response()));
            if (event.failed().isPresent() || event.incomplete().isPresent() || event.error().isPresent())
                throw new IllegalStateException("CHAT_INCOMPLETE_RESPONSE");
        }

        private void start(String itemId) {
            if (started.add(itemId)) turn.events().accept(new ChatSearchEvent(id(itemId), ChatSearchEvent.Stage.STARTED, null));
        }

        private void done(ResponseOutputItem item) {
            item.webSearchCall().ifPresent(this::search);
            item.message().ifPresent(this::cite);
        }

        private void search(ResponseFunctionWebSearch call) {
            start(call.id());
            searched = true;
            lastSearch = id(call.id());
            meters.counter("memoryos.chat.native_web_search.calls", "provider", "openai", "status", call.status().asString()).increment();
            var queries = call.action().search().flatMap(ResponseFunctionWebSearch.Action.Search::queries).orElse(List.of()).stream()
                    .filter(query -> !query.isBlank() && query.length() <= 2000).distinct().limit(8).toList();
            if (!queries.isEmpty())
                turn.events().accept(new ChatSearchEvent(lastSearch, ChatSearchEvent.Stage.SEARCHING, null,
                        new ChatSearchEvent.QueryPlan(queries, new SearchFilters(Set.of(), null, null)), List.of()));
            turn.events().accept(new ChatSearchEvent(lastSearch, ChatSearchEvent.Stage.COMPLETED, null));
        }

        private void cite(ResponseOutputMessage message) {
            for (var content : message.content()) {
                var output = content.outputText().orElse(null);
                if (output == null) continue;
                String text = output.text();
                for (var annotation : output.annotations()) {
                    var citation = annotation.urlCitation().orElse(null);
                    if (citation == null) continue;
                    int start = Math.clamp(citation.startIndex(), 0, text.length());
                    int end = Math.clamp(citation.endIndex(), start, text.length());
                    String excerpt = text.substring(start, Math.min(end, start + 1000));
                    String title = citation.title().isBlank() ? citation.url() : citation.title();
                    try {
                        turn.evidence().register("web:" + citation.url(), number -> new ChatSource(number, null, null,
                                title.substring(0, Math.min(title.length(), 255)), 0, 0, List.of(), null, null,
                                new ChatSource.WebLocation(citation.url(), excerpt, Instant.now())), lastSearch == null ? "web-native" : lastSearch);
                    } catch (IllegalArgumentException unsafeOrInvalid) {
                        // Provider URLs remain untrusted; an unsupported URL is not evidence.
                    }
                }
            }
        }

        private void complete(com.openai.models.responses.Response response) {
            if (required && !searched) throw new IllegalStateException("CHAT_INCOMPLETE_RESPONSE");
            var mapper = ObjectMappers.jsonMapper();
            var calls = new ArrayList<AssistantMessage.ToolCall>();
            var echoed = new ArrayList<>();
            for (var item : response.output()) {
                item.functionCall().ifPresent(call -> calls.add(new AssistantMessage.ToolCall(call.callId(), "function", call.name(), call.arguments())));
                echoed.add(mapper.convertValue(item, Map.class));
            }
            var properties = new LinkedHashMap<String, Object>();
            if (!calls.isEmpty()) properties.put(OUTPUT_ITEMS, JSON.writeValueAsString(echoed));
            var usage = response.usage().map(value -> new DefaultUsage((int) value.inputTokens(), (int) value.outputTokens(), (int) value.totalTokens(), value))
                    .orElseGet(() -> new DefaultUsage(0, 0));
            var output = AssistantMessage.builder().content("").toolCalls(calls).properties(properties).build();
            finished = true;
            sink.next(new ChatResponse(List.of(new Generation(output, ChatGenerationMetadata.builder()
                    .finishReason(calls.isEmpty() ? "stop" : "tool_calls").build())),
                    ChatResponseMetadata.builder().id(response.id()).usage(usage).build()));
            sink.complete();
        }

        private static String id(String itemId) { return "web-native-" + itemId; }
    }
}
