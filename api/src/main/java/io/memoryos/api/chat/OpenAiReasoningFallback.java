package io.memoryos.api.chat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * Chat Completions rejects function tools alongside a reasoning effort for some model families and
 * names the remedy in the rejection itself. A catalog entry whose configured effort predates that
 * constraint would otherwise fail every tool-bearing turn until an administrator edits it, so the
 * rejected request is retried once with the effort the provider demands.
 */
final class OpenAiReasoningFallback implements ChatModel {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAiReasoningFallback.class);
    private static final String DEMAND = "reasoning_effort to 'none'";
    private static final String NONE = "none";
    private final ChatModel delegate;

    OpenAiReasoningFallback(ChatModel delegate) { this.delegate = delegate; }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return delegate.call(prompt);
        } catch (RuntimeException rejected) {
            var retry = withoutReasoning(prompt, rejected);
            if (retry == null) throw rejected;
            return delegate.call(retry);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // A rejected request carries no content and no tool execution, so only an unstarted stream is retried.
        var started = new AtomicBoolean();
        return delegate.stream(prompt).doOnNext(ignored -> started.set(true)).onErrorResume(failure -> {
            if (started.get()) return Flux.error(failure);
            var retry = withoutReasoning(prompt, failure);
            return retry == null ? Flux.error(failure) : delegate.stream(retry);
        });
    }

    private static @Nullable Prompt withoutReasoning(Prompt prompt, Throwable failure) {
        if (!demandsNoReasoning(failure)) return null;
        if (!(prompt.getOptions() instanceof OpenAiChatOptions options)) return null;
        String effort = options.getReasoningEffort();
        if (NONE.equals(effort)) return null;
        LOG.warn("Provider rejected function tools with reasoning effort {}; retrying once with none.", effort);
        return new Prompt(prompt.getInstructions(), options.mutate().reasoningEffort(NONE).build());
    }

    private static boolean demandsNoReasoning(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause.getMessage() != null && cause.getMessage().contains(DEMAND)) return true;
        return false;
    }
}
