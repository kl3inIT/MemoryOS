package io.memoryos.ai.openai;

import com.openai.core.ObjectMappers;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseFunctionWebSearch;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStreamEvent;
import io.memoryos.ai.ModelTurns.Turn;
import io.memoryos.ai.TurnFailure;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.FluxSink;

/**
 * One response stream to {@link ChatResponse} chunks, after Spring AI's {@code ResponsesStreamAssembler}: text
 * deltas as they arrive, reasoning summaries and hosted Web search progress to the turn's listener, and one final
 * chunk with the tool calls, the usage and the response ID. Only {@code function_call} items become tool calls, so
 * the tool loop never looks for a local {@code web_search}.
 */
@NullMarked
final class ResponsesStreamAssembler {
    private static final Logger LOG = LoggerFactory.getLogger(ResponsesStreamAssembler.class);
    private static final String NATIVE_SEARCH = "web-native";

    private final Turn turn;
    private final FluxSink<ChatResponse> sink;
    private final MeterRegistry meters;
    private final Set<String> started = new HashSet<>();
    private @Nullable String lastSearch;
    private boolean finished;
    private boolean streamedText;
    private boolean separate;

    ResponsesStreamAssembler(Turn turn, FluxSink<ChatResponse> sink, MeterRegistry meters) {
        this.turn = turn;
        this.sink = sink;
        this.meters = meters;
    }

    /** Whether the response completed or ended incomplete; a stream that ends without either is a failure. */
    boolean finished() {
        return finished;
    }

    void accept(ResponseStreamEvent event) {
        turn.checkActive().run();
        event.outputTextDelta().ifPresent(delta -> {
            if (!delta.delta().isEmpty()) streamedText = true;
            if (!delta.delta().isEmpty()) sink.next(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(delta.delta()).build()))));
        });
        // Every summary part opens with a bold heading. Parts of a new reasoning item or of the next inference
        // join the same timeline reasoning, so each part is separated, as Onyx's summary newline patch does.
        event.reasoningSummaryPartAdded().ifPresent(_ -> separate = true);
        event.reasoningSummaryTextDelta().ifPresent(delta -> reason(delta.delta()));
        event.webSearchCallInProgress().ifPresent(progress -> start(progress.itemId()));
        event.webSearchCallSearching().ifPresent(progress -> start(progress.itemId()));
        event.outputItemDone().ifPresent(done -> done(done.item()));
        event.completed().ifPresent(completed -> finish(completed.response(), null));
        // Onyx (LiteLLM) ends an incomplete response as a normal stream with finish_reason "length" and keeps what
        // was produced; only a failed response or an error event fails the turn.
        event.incomplete().ifPresent(incomplete -> {
            var response = incomplete.response();
            String reason = response.incompleteDetails().flatMap(details -> details.reason())
                    .map(value -> value.asString()).orElse("unknown");
            LOG.atWarn().addKeyValue("event", "ai.openai.response_incomplete").addKeyValue("response_id", response.id())
                    .addKeyValue("reason", reason).log("OpenAI response ended incomplete");
            // As Onyx, an answer that produced nothing before the model's output limit reports that reason.
            if ("max_output_tokens".equals(reason) && !streamedText && !hasCompletedCall(response))
                throw TurnFailure.MODEL_OUTPUT_LIMIT.exception();
            finish(response, "max_output_tokens".equals(reason) ? "length" : reason);
        });
        if (event.failed().isPresent() || event.error().isPresent())
            throw TurnFailure.INCOMPLETE_RESPONSE.exception();
    }

    private void start(String itemId) {
        if (started.add(itemId)) turn.listener().webSearchStarted(webCall(itemId));
    }

    private void reason(String text) {
        if (text.isEmpty()) return;
        if (separate) {
            separate = false;
            // Markdown ignores the leading blank line of the first part.
            turn.listener().reasoning("\n\n");
        }
        for (int offset = 0; offset < text.length(); ) {
            int end = Math.min(text.length(), offset + 4000);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            turn.listener().reasoning(text.substring(offset, end));
            offset = end;
        }
    }

    private void done(ResponseOutputItem item) {
        item.webSearchCall().ifPresent(this::search);
        item.message().ifPresent(this::cite);
    }

    private void search(ResponseFunctionWebSearch call) {
        start(call.id());
        lastSearch = webCall(call.id());
        meters.counter("memoryos.chat.native_web_search.calls", "provider", "openai", "status", call.status().asString()).increment();
        var queries = call.action().search().flatMap(ResponseFunctionWebSearch.Action.Search::queries).orElse(List.of()).stream()
                .filter(query -> !query.isBlank() && query.length() <= 2000).distinct().limit(8).toList();
        if (!queries.isEmpty())
            turn.listener().webSearchQueries(lastSearch, queries);
        turn.listener().webSearchFinished(lastSearch);
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
                    turn.listener().webCitation(lastSearch == null ? NATIVE_SEARCH : lastSearch, citation.url(),
                            title.substring(0, Math.min(title.length(), 255)), excerpt);
                } catch (IllegalArgumentException unsafeOrInvalid) {
                    // Provider URLs remain untrusted; an unsupported URL is not evidence.
                }
            }
        }
    }

    private static boolean hasCompletedCall(Response response) {
        return response.output().stream().anyMatch(item -> item.functionCall()
                .flatMap(call -> call.status()).map(status -> status.asString()).filter("completed"::equals).isPresent());
    }

    /**
     * {@code incompleteReason} is null for a completed response. An incomplete one neither runs a cut-off tool call
     * nor echoes it: the next request is stateless and would replay the call without a matching output, which the
     * API refuses.
     */
    private void finish(Response response, @Nullable String incompleteReason) {
        var mapper = ObjectMappers.jsonMapper();
        var calls = new ArrayList<AssistantMessage.ToolCall>();
        var echoed = new ArrayList<>();
        for (var item : response.output()) {
            var call = item.functionCall().orElse(null);
            if (call != null && incompleteReason != null
                    && call.status().map(status -> status.asString()).filter("completed"::equals).isEmpty()) continue;
            if (call != null) calls.add(new AssistantMessage.ToolCall(call.callId(), "function", call.name(), call.arguments()));
            echoed.add(mapper.convertValue(item, Map.class));
        }
        var properties = new LinkedHashMap<String, Object>();
        if (!calls.isEmpty()) properties.put(ResponsesInputMapper.OUTPUT_ITEMS, ResponsesInputMapper.echo(echoed));
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

    private static String webCall(String itemId) {
        return "web-native-" + itemId;
    }
}
