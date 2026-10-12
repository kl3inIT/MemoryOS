package io.memoryos.ai.openai;

import io.memoryos.ai.ModelTurns;
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
 * A provider rejects a reasoning effort in two ways and names the remedy in the rejection itself: Chat Completions
 * refuses function tools alongside any effort, and a model family may refuse one effort while listing the ones it
 * supports (the first GPT-5 family has no {@code none}; later ones dropped {@code minimal}). A catalog entry whose
 * effort predates either constraint would otherwise fail every affected turn until an administrator edits it, so the
 * rejected request is retried once with an effort the provider accepts. The Responses route reports its refusal as a
 * {@link Refused} cause, since its failure carries no provider text.
 */
@NullMarked
final class OpenAiReasoningFallback implements ChatModel, ModelTurns {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAiReasoningFallback.class);
    private static final String DEMAND = "reasoning_effort to 'none'";
    private static final String NONE = "none";
    /**
     * "Unsupported value: 'reasoning_effort' does not support 'minimal' ... Supported values are: 'none', 'low', ...";
     * the Responses API names the parameter {@code 'reasoning.effort'}.
     */
    private static final Pattern UNSUPPORTED = Pattern.compile(
            "'reasoning[._]effort' does not support.*?Supported values are:([^.]*)", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");
    /** Helper calls ask for the least reasoning the model offers; the first supported value wins. */
    private static final List<String> CHEAPEST_FIRST = List.of("minimal", "none", "low", "medium", "high", "xhigh");
    private final ChatModel delegate;
    /** The effort accepted in place of each refused one, so a refused effort is sent once, not on every request. */
    private final ConcurrentHashMap<Refusal, String> accepted;

    OpenAiReasoningFallback(ChatModel delegate) { this(delegate, new ConcurrentHashMap<>()); }

    private OpenAiReasoningFallback(ChatModel delegate, ConcurrentHashMap<Refusal, String> accepted) {
        this.delegate = delegate;
        this.accepted = accepted;
    }

    @Override public boolean nativeWebSearch() { return delegate instanceof ModelTurns turns && turns.nativeWebSearch(); }

    /** A turn's view of the route keeps what this model already refused. */
    @Override
    public ChatModel forTurn(Turn turn) {
        return delegate instanceof ModelTurns turns ? new OpenAiReasoningFallback(turns.forTurn(turn), accepted) : this;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        var request = remembered(prompt);
        try {
            return delegate.call(request);
        } catch (RuntimeException rejected) {
            var retry = retry(request, rejected);
            if (retry == null) throw rejected;
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
            var retry = retry(request, failure);
            if (retry == null) return Flux.error(failure);
            return delegate.stream(retry);
        });
    }

    /**
     * Sends a refused effort as the one accepted in its place. Only that effort is replaced: a helper's refused
     * {@code none} must not lower an answer's {@code high}, and a refusal of tools beside an effort leaves the same
     * effort without tools alone.
     */
    private Prompt remembered(Prompt prompt) {
        if (!(prompt.getOptions() instanceof OpenAiChatOptions options)) return prompt;
        String known = accepted.get(Refusal.of(options));
        return known == null ? prompt : new Prompt(prompt.getInstructions(), options.mutate().reasoningEffort(known).build());
    }

    /** The request with the effort the provider accepts, remembered for this refusal, or null when it named none. */
    private @Nullable Prompt retry(Prompt request, Throwable failure) {
        if (!(request.getOptions() instanceof OpenAiChatOptions options)) return null;
        String effort = options.getReasoningEffort();
        String replacement = replacementFor(failure, effort);
        if (replacement == null || replacement.equals(effort)) return null;
        LOG.atWarn().addKeyValue("event", "ai.reasoning_effort.rejected").addKeyValue("reasoning_effort", effort)
                .addKeyValue("replacement", replacement).log("Provider rejected the reasoning effort; retrying once");
        accepted.put(Refusal.of(options), replacement);
        return new Prompt(request.getInstructions(), options.mutate().reasoningEffort(replacement).build());
    }

    /** The effort the provider will accept, read from the rejection, or null when it named none. */
    static @Nullable String replacementFor(Throwable failure, @Nullable String effort) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof Refused refused) return refused.accepted;
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

    /** A refusal belongs to the model, the effort it refused and whether function tools came with that effort. */
    private record Refusal(@Nullable String model, @Nullable String effort, boolean tools) {
        static Refusal of(OpenAiChatOptions options) {
            return new Refusal(options.getModel(), options.getReasoningEffort(),
                    options.getToolCallbacks() != null && !options.getToolCallbacks().isEmpty());
        }
    }

    /** A refused effort reported without the provider's text: only the effort the model accepts instead. */
    static final class Refused extends RuntimeException {
        private final String accepted;

        Refused(String accepted) {
            super("The model accepts reasoning effort " + accepted, null, false, false);
            this.accepted = accepted;
        }
    }
}
