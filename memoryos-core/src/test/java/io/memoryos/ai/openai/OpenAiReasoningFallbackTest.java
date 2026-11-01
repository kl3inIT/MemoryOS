package io.memoryos.ai.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import io.memoryos.ai.ModelTurns;
import io.memoryos.ai.TurnFailure;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

class OpenAiReasoningFallbackTest {
    private static final String REJECTION = "Function tools with reasoning_effort are not supported for "
            + "gpt-5.6-luna in /v1/chat/completions. To use function tools, use /v1/responses or set reasoning_effort to 'none'.";
    private final List<Prompt> sent = new ArrayList<>();

    @Test void aToolRejectionNamingNoneIsRetriedOnceWithThatEffort() {
        var fallback = new OpenAiReasoningFallback(model(prompt -> sent.size() == 1
                ? Flux.error(new IllegalStateException(new RuntimeException(REJECTION)))
                : Flux.just(response())));
        assertEquals("OK", fallback.stream(prompt("medium")).blockLast().getResult().getOutput().getText());
        assertEquals(List.of("medium", "none"), efforts());
    }

    @Test void anUnrelatedRejectionAndAStreamThatAlreadyProducedContentAreNotRetried() {
        var unrelated = new OpenAiReasoningFallback(model(prompt -> Flux.error(new IllegalStateException("context length exceeded"))));
        assertThrows(IllegalStateException.class, () -> unrelated.stream(prompt("medium")).blockLast());
        assertEquals(List.of("medium"), efforts());
        sent.clear();
        var started = new OpenAiReasoningFallback(model(prompt -> Flux.concat(Flux.just(response()),
                Flux.error(new IllegalStateException(REJECTION)))));
        assertThrows(IllegalStateException.class, () -> started.stream(prompt("medium")).blockLast());
        assertEquals(List.of("medium"), efforts());
    }

    @Test void anUnsupportedEffortIsRetriedWithTheCheapestValueTheModelLists() {
        // Staging hit this on gpt-5.6-sol: every research agent inference asks for the helper effort "minimal".
        String rejection = "Unsupported value: 'reasoning_effort' does not support 'minimal' with this model. "
                + "Supported values are: 'none', 'low', 'medium', 'high', and 'xhigh'.";
        var fallback = new OpenAiReasoningFallback(model(prompt -> sent.size() == 1
                ? Flux.error(new IllegalStateException(new RuntimeException(rejection)))
                : Flux.just(response())));
        assertEquals("OK", fallback.stream(prompt("minimal")).blockLast().getResult().getOutput().getText());
        assertEquals(List.of("minimal", "none"), efforts());
    }

    @Test void aModelThatStillListsMinimalKeepsTheCheapestReasoningRatherThanNone() {
        String rejection = "Unsupported value: 'reasoning_effort' does not support 'xhigh' with this model. "
                + "Supported values are: 'minimal', 'low' and 'medium'.";
        var fallback = new OpenAiReasoningFallback(model(prompt -> sent.size() == 1
                ? Flux.error(new IllegalStateException(rejection))
                : Flux.just(response())));
        assertEquals("OK", fallback.stream(prompt("xhigh")).blockLast().getResult().getOutput().getText());
        assertEquals(List.of("xhigh", "minimal"), efforts());
    }

    @Test void aRejectionThatNamesNoSupportedValueIsNotRetried() {
        var fallback = new OpenAiReasoningFallback(model(prompt ->
                Flux.error(new IllegalStateException("Unsupported value: 'reasoning_effort' does not support 'minimal'."))));
        assertThrows(IllegalStateException.class, () -> fallback.stream(prompt("minimal")).blockLast());
        assertEquals(List.of("minimal"), efforts());
    }

    @Test void aRequestAlreadySentWithoutReasoningIsNotRetried() {
        var fallback = new OpenAiReasoningFallback(model(prompt -> Flux.error(new IllegalStateException(REJECTION))));
        assertThrows(IllegalStateException.class, () -> fallback.stream(prompt("none")).blockLast());
        assertEquals(List.of("none"), efforts());
    }

    @Test void theAcceptedEffortIsReusedSoLaterRequestsSkipTheRejection() {
        // Research issues one helper inference per agent cycle; repeating the refused effort would cost a round trip each time.
        String rejection = "Unsupported value: 'reasoning_effort' does not support 'minimal' with this model. "
                + "Supported values are: 'none', 'low', 'medium'.";
        var fallback = new OpenAiReasoningFallback(model(prompt ->
                "minimal".equals(((OpenAiChatOptions) prompt.getOptions()).getReasoningEffort())
                        ? Flux.error(new IllegalStateException(rejection))
                        : Flux.just(response())));
        assertEquals("OK", fallback.stream(prompt("minimal")).blockLast().getResult().getOutput().getText());
        assertEquals("OK", fallback.stream(prompt("minimal")).blockLast().getResult().getOutput().getText());
        assertEquals(List.of("minimal", "none", "none"), efforts());
    }

    @Test void aRefusedNoneIsRetriedWithMinimalAndOnlyThatEffortIsReplacedLater() {
        // The first GPT-5 family refuses the helper default none; an answer's own effort must still be sent as configured.
        String rejection = "Unsupported value: 'reasoning_effort' does not support 'none' with this model. "
                + "Supported values are: 'minimal', 'low', 'medium', and 'high'.";
        var fallback = new OpenAiReasoningFallback(model(prompt -> "none".equals(effortOf(prompt))
                ? Flux.error(new IllegalStateException(rejection))
                : Flux.just(response())));
        fallback.stream(prompt("none")).blockLast();
        fallback.stream(prompt("none")).blockLast();
        fallback.call(prompt("high"));
        assertEquals(List.of("none", "minimal", "minimal", "high"), efforts());
    }

    @Test void aToolRefusalIsRememberedOnlyForTheSameEffortWithTools() {
        var fallback = new OpenAiReasoningFallback(model(prompt -> {
            var options = (OpenAiChatOptions) prompt.getOptions();
            return !options.getToolCallbacks().isEmpty() && !"none".equals(options.getReasoningEffort())
                    ? Flux.error(new IllegalStateException(REJECTION))
                    : Flux.just(response());
        }));
        fallback.stream(prompt("medium", true)).blockLast();
        fallback.stream(prompt("medium", true)).blockLast();
        fallback.stream(prompt("medium", false)).blockLast();
        assertEquals(List.of("medium", "none", "none", "medium"), efforts());
    }

    @Test void aRefusalTheResponsesRouteReportsIsRetriedAndATurnKeepsWhatWasLearned() {
        // The Responses route fails without provider text; its cause names only the effort the model accepts.
        var route = new Route(model(prompt -> "none".equals(effortOf(prompt))
                ? Flux.error(TurnFailure.PROVIDER_UNAVAILABLE.exception().initCause(new OpenAiReasoningFallback.Refused("minimal")))
                : Flux.just(response())));
        var fallback = new OpenAiReasoningFallback(route);
        assertTrue(fallback.nativeWebSearch());
        fallback.stream(prompt("none")).blockLast();
        fallback.forTurn(new ModelTurns.Turn(ModelTurns.Listener.NONE, true, () -> {})).stream(prompt("none")).blockLast();
        assertEquals(List.of("none", "minimal", "minimal"), efforts());
    }

    private List<String> efforts() {
        return sent.stream().map(prompt -> ((OpenAiChatOptions) prompt.getOptions()).getReasoningEffort()).toList();
    }

    private static String effortOf(Prompt prompt) {
        return ((OpenAiChatOptions) prompt.getOptions()).getReasoningEffort();
    }

    private static Prompt prompt(String effort) {
        return prompt(effort, false);
    }

    private static Prompt prompt(String effort, boolean tools) {
        return new Prompt(List.of(new UserMessage("Question")), OpenAiChatOptions.builder().model("gpt-5.6-luna")
                .reasoningEffort(effort).toolCallbacks(tools ? List.of(mock(ToolCallback.class)) : List.of()).build());
    }

    private static ChatResponse response() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("OK"))));
    }

    /** A provider route with a per-turn view, as the Responses route is. */
    private record Route(ChatModel model) implements ChatModel, ModelTurns {
        @Override public ChatResponse call(Prompt prompt) { return model.call(prompt); }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return model.stream(prompt); }
        @Override public ChatModel forTurn(Turn turn) { return this; }
        @Override public boolean nativeWebSearch() { return true; }
    }

    private ChatModel model(Function<Prompt, Flux<ChatResponse>> responses) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                sent.add(prompt);
                return responses.apply(prompt).blockLast();
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                sent.add(prompt);
                return Flux.defer(() -> responses.apply(prompt));
            }
        };
    }
}
