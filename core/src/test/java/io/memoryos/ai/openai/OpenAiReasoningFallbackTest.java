package io.memoryos.ai.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
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

    private List<String> efforts() {
        return sent.stream().map(prompt -> ((OpenAiChatOptions) prompt.getOptions()).getReasoningEffort()).toList();
    }

    private static Prompt prompt(String effort) {
        return new Prompt(List.of(new UserMessage("Question")),
                OpenAiChatOptions.builder().model("gpt-5.6-luna").reasoningEffort(effort).build());
    }

    private static ChatResponse response() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("OK"))));
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
