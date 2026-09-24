package io.memoryos.chat.catalog.openai;

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
import io.memoryos.chat.ChatReasoningDelta;
import io.memoryos.chat.ChatToolEvent;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.execution.ChatModelTurns;
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
 * OpenAI Responses API for Chat turns. As Onyx, every streamed turn of a model served by OpenAI itself uses it
 * ({@code always}), because only this API streams reasoning summaries and accepts tools beside reasoning; an
 * OpenAI-compatible endpoint uses it only for hosted {@code web_search} or configured summaries, and there unbound
 * streams keep the Chat Completions delegate. Synchronous helpers always do. Requests are stateless ({@code store=false}); output items
 * needed by the next tool cycle ride in assistant message properties.
 */
@org.jspecify.annotations.NullMarked
final class OpenAiResponsesChatModel implements ChatModel, ChatModelTurns {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(OpenAiResponsesChatModel.class);
    static final String OUTPUT_ITEMS = "memoryos.openai.responses.output";
    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private final ChatModel completions;
    private final OpenAIClientAsync client;
    private final boolean reasoning;
    private final boolean webSearch;
    private final boolean summaries;
    private final boolean always;
    private final MeterRegistry meters;
    private final @Nullable Turn turn;

    OpenAiResponsesChatModel(ChatModel completions, OpenAIClientAsync client, boolean reasoning, MeterRegistry meters) {
        this(completions, client, reasoning, true, false, meters);
    }

    OpenAiResponsesChatModel(ChatModel completions, OpenAIClientAsync client, boolean reasoning, boolean webSearch, boolean summaries, MeterRegistry meters) {
        this(completions, client, reasoning, webSearch, summaries, false, meters, null);
    }

    OpenAiResponsesChatModel(ChatModel completions, OpenAIClientAsync client, boolean reasoning, boolean webSearch, boolean summaries,
                             boolean always, MeterRegistry meters) {
        this(completions, client, reasoning, webSearch, summaries, always, meters, null);
    }

    private OpenAiResponsesChatModel(ChatModel completions, OpenAIClientAsync client, boolean reasoning, boolean webSearch, boolean summaries,
                                     boolean always, MeterRegistry meters, @Nullable Turn turn) {
        this.completions = completions;
        this.client = client;
        this.reasoning = reasoning;
        this.webSearch = webSearch;
        this.summaries = summaries && reasoning;
        this.always = always;
        this.meters = meters;
        this.turn = turn;
    }

    @Override public ChatModel forTurn(Turn value) { return new OpenAiResponsesChatModel(completions, client, reasoning, webSearch, summaries, always, meters, value); }

    @Override public boolean nativeWebSearch() { return webSearch; }

    /** Synchronous helpers (selection, classification) never search the Web. */
    @Override public ChatResponse call(Prompt prompt) { return completions.call(prompt); }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (turn == null && !always) return completions.stream(prompt);
        // Unbound streams (connection validation) probe the same route turns use; they publish no activity.
        var active = turn != null ? turn : new Turn(new io.memoryos.chat.ChatEvidence(), ignored -> {}, false, () -> {});
        boolean web = webSearch && active.webSearch();
        if (!web && !summaries && !always) return completions.stream(prompt);
        if (!(prompt.getOptions() instanceof OpenAiChatOptions options)) return Flux.error(new IllegalArgumentException("CHAT_UNSUPPORTED_OPTIONS"));
        // The final-cycle policy removes every tool callback; hosted search is a tool as well.
        boolean tools = options.getToolCallbacks() != null && !options.getToolCallbacks().isEmpty();
        ResponseCreateParams params;
        try { params = request(prompt, options, tools, web); }
        catch (RuntimeException invalid) { return Flux.error(new IllegalArgumentException("CHAT_UNSUPPORTED_OPTIONS")); }
        return Flux.create(sink -> {
            var state = new StreamState(active, sink);
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

    private ResponseCreateParams request(Prompt prompt, OpenAiChatOptions options, boolean tools, boolean web) {
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
        var reasoningOptions = new LinkedHashMap<String, Object>();
        // Onyx ReasoningEffort.AUTO is "medium" for OpenAI (onyx/llm/models.py). Omitting it lets GPT-5.1 and later
        // default to no reasoning, so a reasoning model neither thought nor streamed a summary while it worked.
        String effort = options.getReasoningEffort() != null ? options.getReasoningEffort() : reasoning ? "medium" : null;
        if (effort != null) reasoningOptions.put("effort", effort);
        // As Onyx, summaries accompany every reasoning request, so the stream carries packets while the model thinks.
        if (summaries && !"none".equals(effort)) reasoningOptions.put("summary", "auto");
        if (!reasoningOptions.isEmpty()) builder.putAdditionalBodyProperty("reasoning", JsonValue.from(reasoningOptions));
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
            if (web) declared.add(mapper.convertValue(Map.of("type", "web_search"), Tool.class));
            builder.tools(declared);
            Object choice = toolChoice(options.getToolChoice());
            if (choice != null) builder.putAdditionalBodyProperty("tool_choice", JsonValue.from(choice));
            if (options.getParallelToolCalls() != null) builder.parallelToolCalls(options.getParallelToolCalls());
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

    /**
     * A Chat Completions tool choice as Responses expects it: {@code auto}, {@code none} and {@code required} are the
     * same strings; a named function moves its name from {@code function.name} to the top level.
     */
    static @Nullable Object toolChoice(@Nullable Object choice) {
        if (choice == null) return null;
        Object value = choice instanceof String text && text.trim().startsWith("{") ? JSON.readValue(text, Map.class) : choice;
        if (value instanceof String text) {
            if (!Set.of("auto", "none", "required").contains(text)) throw new IllegalArgumentException("Unsupported tool choice");
            return text;
        }
        Map<?, ?> map = value instanceof Map<?, ?> direct ? direct : JSON.convertValue(value, Map.class);
        if (!"function".equals(map.get("type"))) throw new IllegalArgumentException("Unsupported tool choice");
        if (map.get("function") instanceof Map<?, ?> named && named.get("name") instanceof String name) return Map.of("type", "function", "name", name);
        if (map.get("name") instanceof String name) return Map.of("type", "function", "name", name);
        throw new IllegalArgumentException("Unsupported tool choice");
    }

    private static String text(@Nullable String value) { return value == null ? "" : value; }

    private final class StreamState {
        private final Turn turn;
        private final FluxSink<ChatResponse> sink;
        private final Set<String> started = new HashSet<>();
        private ChatToolEvent.@Nullable Call lastSearch;
        private boolean searched;
        private boolean finished;
        private boolean streamedText;
        private boolean separate;

        StreamState(Turn turn, FluxSink<ChatResponse> sink) {
            this.turn = turn;
            this.sink = sink;
        }

        void accept(ResponseStreamEvent event) {
            turn.checkActive().run();
            event.outputTextDelta().ifPresent(delta -> {
                if (!delta.delta().isEmpty()) streamedText = true;
                if (!delta.delta().isEmpty()) sink.next(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(delta.delta()).build()))));
            });
            // Every summary part opens with a bold heading. Parts of a new reasoning item or of the next inference
            // join the same timeline reasoning, so each part is separated, as Onyx's summary newline patch does.
            event.reasoningSummaryPartAdded().ifPresent(part -> separate = true);
            event.reasoningSummaryTextDelta().ifPresent(delta -> reason(delta.delta()));
            event.webSearchCallInProgress().ifPresent(progress -> start(progress.itemId()));
            event.webSearchCallSearching().ifPresent(progress -> start(progress.itemId()));
            event.outputItemDone().ifPresent(done -> done(done.item()));
            event.completed().ifPresent(completed -> complete(completed.response()));
            // Onyx (LiteLLM) ends an incomplete response as a normal stream with finish_reason "length" and keeps what
            // was produced; only a failed response or an error event fails the turn.
            event.incomplete().ifPresent(incomplete -> {
                var response = incomplete.response();
                String reason = response.incompleteDetails().flatMap(details -> details.reason())
                        .map(value -> value.asString()).orElse("unknown");
                LOG.warn("OpenAI response {} ended incomplete: {}", response.id(), reason);
                // As Onyx, an answer that produced nothing before the model's output limit reports that reason.
                if ("max_output_tokens".equals(reason) && !streamedText && !hasCompletedCall(response))
                    throw new IllegalStateException("CHAT_MODEL_OUTPUT_LIMIT");
                finish(response, "max_output_tokens".equals(reason) ? "length" : reason);
            });
            if (event.failed().isPresent() || event.error().isPresent())
                throw new IllegalStateException("CHAT_INCOMPLETE_RESPONSE");
        }

        private void start(String itemId) {
            if (started.add(itemId)) turn.events().accept(new ChatToolEvent(webCall(itemId), ChatToolEvent.Stage.STARTED));
        }

        private void reason(String text) {
            if (text.isEmpty()) return;
            if (separate) {
                separate = false;
                // Markdown ignores the leading blank line of the first part.
                turn.events().accept(new ChatReasoningDelta("\n\n"));
            }
            for (int offset = 0; offset < text.length(); ) {
                int end = Math.min(text.length(), offset + 4000);
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                turn.events().accept(new ChatReasoningDelta(text.substring(offset, end)));
                offset = end;
            }
        }

        private void done(ResponseOutputItem item) {
            item.webSearchCall().ifPresent(this::search);
            item.message().ifPresent(this::cite);
        }

        private void search(ResponseFunctionWebSearch call) {
            start(call.id());
            searched = true;
            lastSearch = webCall(call.id());
            meters.counter("memoryos.chat.native_web_search.calls", "provider", "openai", "status", call.status().asString()).increment();
            var queries = call.action().search().flatMap(ResponseFunctionWebSearch.Action.Search::queries).orElse(List.of()).stream()
                    .filter(query -> !query.isBlank() && query.length() <= 2000).distinct().limit(8).toList();
            if (!queries.isEmpty())
                turn.events().accept(new ChatToolEvent(lastSearch,
                        new ChatToolEvent.QueryPlan(queries, new SearchFilters(Set.of(), null, null))));
            turn.events().accept(ChatToolEvent.finished(lastSearch, false, null));
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
                                new ChatSource.WebLocation(citation.url(), excerpt, Instant.now())), lastSearch == null ? NATIVE_SEARCH : lastSearch);
                    } catch (IllegalArgumentException unsafeOrInvalid) {
                        // Provider URLs remain untrusted; an unsupported URL is not evidence.
                    }
                }
            }
        }

        private static boolean hasCompletedCall(com.openai.models.responses.Response response) {
            return response.output().stream().anyMatch(item -> item.functionCall()
                    .flatMap(call -> call.status()).map(status -> status.asString()).filter("completed"::equals).isPresent());
        }

        private void complete(com.openai.models.responses.Response response) {
            finish(response, null);
        }

        /** {@code incompleteReason} is null for a completed response; an incomplete one never runs a cut-off tool call. */
        private void finish(com.openai.models.responses.Response response, @Nullable String incompleteReason) {
            var mapper = ObjectMappers.jsonMapper();
            var calls = new ArrayList<AssistantMessage.ToolCall>();
            var echoed = new ArrayList<>();
            for (var item : response.output()) {
                item.functionCall().ifPresent(call -> {
                    if (incompleteReason == null || call.status().map(status -> status.asString()).filter("completed"::equals).isPresent())
                        calls.add(new AssistantMessage.ToolCall(call.callId(), "function", call.name(), call.arguments()));
                });
                echoed.add(mapper.convertValue(item, Map.class));
            }
            var properties = new LinkedHashMap<String, Object>();
            if (!calls.isEmpty()) properties.put(OUTPUT_ITEMS, JSON.writeValueAsString(echoed));
            // Cached input tokens are reported so AI usage can show them; they are part of inputTokens, not in addition.
            var usage = response.usage().map(value -> new DefaultUsage((int) value.inputTokens(), (int) value.outputTokens(), (int) value.totalTokens(), value,
                            value.inputTokensDetails().cachedTokens(), null))
                    .orElseGet(() -> new DefaultUsage(0, 0));
            var output = AssistantMessage.builder().content("").toolCalls(calls).properties(properties).build();
            finished = true;
            sink.next(new ChatResponse(List.of(new Generation(output, ChatGenerationMetadata.builder()
                    .finishReason(!calls.isEmpty() ? "tool_calls" : incompleteReason != null ? incompleteReason : "stop").build())),
                    ChatResponseMetadata.builder().id(response.id()).usage(usage).build()));
            sink.complete();
        }

        private static final ChatToolEvent.Call NATIVE_SEARCH = new ChatToolEvent.Call("web-native", "web_search");

        private static ChatToolEvent.Call webCall(String itemId) { return new ChatToolEvent.Call("web-native-" + itemId, "web_search"); }
    }
}
