package io.memoryos.ai.openai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * Chat Completions rejects a reasoning effort in two ways and names the remedy in the rejection itself: function tools
 * are refused alongside any effort, and a model family may refuse one effort while listing the ones it supports. A
 * catalog entry whose configured effort predates either constraint would otherwise fail every affected turn until an
 * administrator edits it, so the rejected request is retried once with an effort the provider accepts.
 */
@NullMarked
final class OpenAiReasoningFallback implements ChatModel {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAiReasoningFallback.class);
    private static final String DEMAND = "reasoning_effort to 'none'";
    private static final String NONE = "none";
    /** "Unsupported value: 'reasoning_effort' does not support 'minimal' ... Supported values are: 'none', 'low', ..." */
    private static final Pattern UNSUPPORTED = Pattern.compile(
            "'reasoning_effort' does not support.*?Supported values are:([^.]*)", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");
    /** Helper calls ask for the least reasoning the model offers; the first supported value wins. */
    private static final List<String> CHEAPEST_FIRST = List.of("minimal", "none", "low", "medium", "high", "xhigh");
    private final ChatModel delegate;
    /** What each model accepted after a rejection, so later requests do not repeat the refused effort. */
    private final ConcurrentHashMap<String, String> accepted = new ConcurrentHashMap<>();

    OpenAiReasoningFallback(ChatModel delegate) { this.delegate = delegate; }

    @Override
    public ChatResponse call(Prompt prompt) {
        var request = remembered(prompt);
        try {
            return delegate.call(request);
        } catch (RuntimeException rejected) {
            var retry = withoutReasoning(request, rejected);
            if (retry == null) throw rejected;
            remember(retry);
            return delegate.call(retry);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // A rejected request carries no content and no tool execution, so only an unstarted stream is retried.
        var started = new AtomicBoolean();
        var request = remembered(prompt);
        return delegate.stream(request).doOnNext(ignored -> started.set(true)).onErrorResume(failure -> {
            if (started.get()) return Flux.error(failure);
            var retry = withoutReasoning(request, failure);
            if (retry == null) return Flux.error(failure);
            remember(retry);
            return delegate.stream(retry);
        });
    }

    /** Applies the effort this model accepted earlier, so a refused effort is sent once, not on every request. */
    private Prompt remembered(Prompt prompt) {
        if (!(prompt.getOptions() instanceof OpenAiChatOptions options)) return prompt;
        String model = options.getModel();
        String known = accepted.get(model);
        if (known == null || known.equals(options.getReasoningEffort())) return prompt;
        return new Prompt(prompt.getInstructions(), options.mutate().reasoningEffort(known).build());
    }

    private void remember(Prompt retry) {
        if (retry.getOptions() instanceof OpenAiChatOptions options && options.getReasoningEffort() != null)
            accepted.put(options.getModel(), options.getReasoningEffort());
    }

    private static @Nullable Prompt withoutReasoning(Prompt prompt, Throwable failure) {
        if (!(prompt.getOptions() instanceof OpenAiChatOptions options)) return null;
        String effort = options.getReasoningEffort();
        String replacement = replacementFor(failure, effort);
        if (replacement == null || replacement.equals(effort)) return null;
        LOG.atWarn().addKeyValue("event", "ai.reasoning_effort.rejected").addKeyValue("reasoning_effort", effort)
                .addKeyValue("replacement", replacement).log("Provider rejected the reasoning effort; retrying once");
        return new Prompt(prompt.getInstructions(), options.mutate().reasoningEffort(replacement).build());
    }

    /** The effort the provider will accept, read from the rejection, or null when it named none. */
    private static @Nullable String replacementFor(Throwable failure, @Nullable String effort) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) continue;
            if (message.contains(DEMAND)) return NONE.equals(effort) ? null : NONE;
            var supported = supportedValues(message);
            if (supported.isEmpty()) continue;
            for (String candidate : CHEAPEST_FIRST)
                if (supported.contains(candidate)) return candidate;
        }
        return null;
    }

    private static Set<String> supportedValues(String message) {
        var rejection = UNSUPPORTED.matcher(message);
        if (!rejection.find()) return Set.of();
        var values = new LinkedHashSet<String>();
        Matcher value = QUOTED.matcher(rejection.group(1));
        while (value.find()) values.add(value.group(1));
        return values;
    }
}
