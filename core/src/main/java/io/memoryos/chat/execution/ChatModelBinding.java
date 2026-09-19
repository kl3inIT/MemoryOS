package io.memoryos.chat.execution;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A configured native model and immutable request policy. No run state or client cache. { requiredTools} makes a
 * request with tools require a tool call (Onyx { tool_choice=REQUIRED} on research cycles); an adapter without it
 * leaves requests unchanged.
 */
public record ChatModelBinding(SpringAiLlmService service, UnaryOperator<Prompt> finalRequest,
                               ChatRequestPolicy policy, int contextWindow, @Nullable Integer maxOutputTokens, boolean toolCalling, boolean vision,
                               UnaryOperator<Prompt> requiredTools) {
    public ChatModelBinding(SpringAiLlmService service, UnaryOperator<Prompt> finalRequest,
                            ChatRequestPolicy policy, int contextWindow, @Nullable Integer maxOutputTokens, boolean toolCalling, boolean vision) {
        this(service, finalRequest, policy, contextWindow, maxOutputTokens, toolCalling, vision, UnaryOperator.identity());
    }
    public ChatModelBinding forOptions(io.memoryos.chat.ChatTurnOptions options) {
        return new ChatModelBinding(service, finalRequest, policy, contextWindow,
                options.outputTokenLimit() == null ? maxOutputTokens : Integer.valueOf(outputAtMost(options.outputTokenLimit())),
                toolCalling, vision, requiredTools);
    }
    public ChatModelBinding {
        Objects.requireNonNull(service);
        Objects.requireNonNull(finalRequest);
        Objects.requireNonNull(requiredTools);
        Objects.requireNonNull(policy);
        if (maxOutputTokens != null && (maxOutputTokens < 1 || contextWindow <= maxOutputTokens))
            throw new IllegalArgumentException("Invalid model limits");
    }

    /** Onyx {@code GEN_AI_INPUT_TOKEN_SAFETY_MARGIN}: estimates can undercount the provider's tokenizer. */
    public static final double INPUT_SAFETY_MARGIN = 0.05;
    /** Onyx {@code GEN_AI_MODEL_FALLBACK_MAX_TOKENS}, its output limit for a model nobody describes. */
    public static final int FALLBACK_OUTPUT_TOKENS = 32_000;

    /**
     * As Onyx {@code llm_loop}: the model's input window, less the answer reserve, held {@link #INPUT_SAFETY_MARGIN}
     * below it. No deployment cap applies unless one is configured.
     */
    public int inputLimit(int outputReserve) {
        return (int) ((contextWindow - outputAtMost(outputReserve)) * (1 - INPUT_SAFETY_MARGIN));
    }

    /**
     * A number for work that must name an output bound (cost reservation, research inferences, helpers): the model's
     * own limit, else Onyx's 32,000-token fallback kept to a quarter of the window.
     */
    public int outputBound() {
        return maxOutputTokens != null ? maxOutputTokens : Math.min(FALLBACK_OUTPUT_TOKENS, contextWindow / 4);
    }

    /** {@code bound}, lowered to the model's output limit when it publishes one. */
    public int outputAtMost(int bound) {
        return maxOutputTokens == null ? bound : Math.min(bound, maxOutputTokens);
    }

    /** Contributions were frozen into the admitted system message before reserving this turn. */
    public SpringAiLlmService withModel(ChatModel model) {
        return new SpringAiLlmService(service.getName(), service.getProvider(), model, service.getOptionsConverter(),
                service.getKnowledgeCutoffDate(), List.of(), service.getPricingModel(),
                service.supportsThinking(), service.getToolResponseContentAdapter(),
                service.getNativeStructuredOutputConfigurer(), service.getNativeSupport());
    }
}
