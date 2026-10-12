package io.memoryos.ai.openai;

import com.openai.client.OpenAIClientAsync;
import com.openai.core.http.AsyncStreamResponse;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseStreamEvent;
import io.memoryos.ai.ModelTurns;
import io.memoryos.ai.TurnFailure;
import io.memoryos.ai.TurnFailureException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * OpenAI Responses API for Chat turns. As Onyx, every streamed turn of a model served by OpenAI itself uses it
 * ({@code always}), because only this API streams reasoning summaries and accepts tools beside reasoning; an
 * OpenAI-compatible endpoint uses it only for hosted {@code web_search} or configured summaries, and there unbound
 * streams keep the Chat Completions delegate. Synchronous helpers always do.
 *
 * <p>Structured after Spring AI's {@code OpenAiResponsesChatModel}, which replaces it when MemoryOS moves to
 * Spring AI 2.1: this class only routes and subscribes; {@link ResponsesRequestBuilder} builds the request,
 * {@link ResponsesInputMapper} maps messages and echoes output items, and {@link ResponsesStreamAssembler} turns the
 * stream into chunks.
 */
@NullMarked
final class OpenAiResponsesChatModel implements ChatModel, ModelTurns {
    private final ChatModel completions;
    private final OpenAIClientAsync client;
    private final ResponsesRequestBuilder requests;
    private final boolean webSearch;
    private final boolean always;
    private final MeterRegistry meters;
    private final @Nullable Turn turn;

    OpenAiResponsesChatModel(ChatModel completions, OpenAIClientAsync client, boolean reasoning, boolean webSearch, boolean summaries,
                             boolean always, MeterRegistry meters) {
        this(completions, client, new ResponsesRequestBuilder(reasoning, summaries), webSearch, always, meters, null);
    }

    private OpenAiResponsesChatModel(ChatModel completions, OpenAIClientAsync client, ResponsesRequestBuilder requests,
                                     boolean webSearch, boolean always, MeterRegistry meters, @Nullable Turn turn) {
        this.completions = completions;
        this.client = client;
        this.requests = requests;
        this.webSearch = webSearch;
        this.always = always;
        this.meters = meters;
        this.turn = turn;
    }

    @Override public ChatModel forTurn(Turn value) { return new OpenAiResponsesChatModel(completions, client, requests, webSearch, always, meters, value); }

    @Override public boolean nativeWebSearch() { return webSearch; }

    /** Synchronous helpers (query rewrites, filters, selection) never search the Web. */
    @Override public ChatResponse call(Prompt prompt) { return completions.call(prompt); }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (turn == null && !always) return completions.stream(prompt);
        // Unbound streams (connection validation) probe the same route turns use; they publish no activity.
        var active = turn != null ? turn : new Turn(Listener.NONE, false, () -> {});
        boolean web = webSearch && active.webSearch();
        if (!web && !requests.summaries() && !always) return completions.stream(prompt);
        if (!(prompt.getOptions() instanceof OpenAiChatOptions options)) return Flux.error(TurnFailure.UNSUPPORTED_OPTIONS.exception());
        // The final-cycle policy removes every tool callback; hosted search is a tool as well.
        boolean tools = options.getToolCallbacks() != null && !options.getToolCallbacks().isEmpty();
        ResponseCreateParams params;
        try { params = requests.build(prompt, options, tools, web); }
        catch (RuntimeException invalid) { return Flux.error(TurnFailure.UNSUPPORTED_OPTIONS.exception()); }
        return Flux.create(sink -> {
            var assembler = new ResponsesStreamAssembler(active, sink, meters);
            AsyncStreamResponse<ResponseStreamEvent> stream = client.responses().createStreaming(params);
            sink.onDispose(stream::close);
            stream.subscribe(new AsyncStreamResponse.Handler<>() {
                @Override public void onNext(ResponseStreamEvent event) {
                    try { assembler.accept(event); } catch (RuntimeException failure) { sink.error(failure); }
                }
                @Override public void onComplete(Optional<Throwable> error) {
                    if (error.isPresent()) sink.error(failure(error.get(), options.getReasoningEffort()));
                    else if (!assembler.finished()) sink.error(TurnFailure.INCOMPLETE_RESPONSE.exception());
                }
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /** A provider failure without its text; a refused reasoning effort also names the effort the model accepts. */
    private static TurnFailureException failure(Throwable error, @Nullable String effort) {
        if (OpenAiFailures.credentialRejected(error)) return TurnFailure.PROVIDER_CREDENTIAL_REJECTED.exception();
        var failure = TurnFailure.PROVIDER_UNAVAILABLE.exception();
        String accepted = OpenAiReasoningFallback.replacementFor(error, effort);
        if (accepted != null && !accepted.equals(effort)) failure.initCause(new OpenAiReasoningFallback.Refused(accepted));
        return failure;
    }
}
