package io.memoryos.ai.openai;

import io.memoryos.ai.ModelTurns;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Reasoning on the Chat Completions route. OpenAI-compatible providers stream it beside the answer as
 * {@code delta.reasoning} (OpenRouter) or {@code delta.reasoning_content} (DeepSeek, vLLM, Ollama, LM Studio); Spring AI
 * accumulates either into the assistant metadata {@code reasoningContent}. As Onyx, whose LiteLLM normalizes both into
 * {@code reasoning_content} and emits reasoning packets, the new part of each chunk is published as turn reasoning, so
 * the answer shows what the model is thinking instead of standing still.
 */
final class ChatCompletionsReasoning implements ChatModel, ModelTurns {
    static final String REASONING = "reasoningContent";
    private static final int MAX_EVENT_CHARACTERS = 4000;
    private final ChatModel delegate;

    ChatCompletionsReasoning(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override public ChatResponse call(Prompt prompt) { return delegate.call(prompt); }
    @Override public Flux<ChatResponse> stream(Prompt prompt) { return delegate.stream(prompt); }
    @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }
    @Override public boolean nativeWebSearch() { return false; }
    @Override public ChatModel forTurn(Turn turn) { return new PerTurn(turn); }

    private final class PerTurn implements ChatModel {
        private final Turn turn;

        PerTurn(Turn turn) {
            this.turn = turn;
        }

        @Override public ChatResponse call(Prompt prompt) { return delegate.call(prompt); }
        @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> {
                var published = new StringBuilder();
                return delegate.stream(prompt).doOnNext(response -> publish(response, published));
            });
        }

        /** The metadata holds everything reasoned so far in this inference; only the new part is published. */
        private void publish(ChatResponse response, StringBuilder published) {
            if (response.getResult() == null) return;
            Object value = response.getResult().getOutput().getMetadata().get(REASONING);
            if (!(value instanceof String reasoning) || reasoning.length() <= published.length()) return;
            if (!reasoning.startsWith(published.toString())) published.setLength(0);
            String part = reasoning.substring(published.length());
            // Each inference's reasoning starts a paragraph, as the Responses route separates summary parts.
            if (published.isEmpty()) turn.listener().reasoning("\n\n");
            published.append(part);
            for (int offset = 0; offset < part.length(); ) {
                int end = Math.min(part.length(), offset + MAX_EVENT_CHARACTERS);
                if (end < part.length() && Character.isHighSurrogate(part.charAt(end - 1))) end--;
                turn.listener().reasoning(part.substring(offset, end));
                offset = end;
            }
        }
    }
}
