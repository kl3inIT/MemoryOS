package io.memoryos.ai;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A configured native model and immutable request policy. No run state or client cache. { requiredTools} makes a
 * request with tools require a tool call (Onyx { tool_choice=REQUIRED} on research cycles); an adapter without it
 * leaves requests unchanged.
 */
public record ModelBinding(SpringAiLlmService service, UnaryOperator<Prompt> finalRequest,
                               ModelRequestPolicy policy, int contextWindow, @Nullable Integer maxOutputTokens, boolean toolCalling, boolean vision,
                               UnaryOperator<Prompt> requiredTools,
                               BiFunction<SpringAiLlmService, ModelSampling, SpringAiLlmService> sampling,
                               Predicate<Throwable> credentialRejection, @Nullable ReasoningEffort taskEffort) {
    /** A binding that leaves tool requests unchanged and has no per-turn sampling. */
    public static Builder builder(SpringAiLlmService service, UnaryOperator<Prompt> finalRequest, ModelRequestPolicy policy,
                                  int contextWindow, @Nullable Integer maxOutputTokens, boolean toolCalling, boolean vision) {
        return new Builder(service, finalRequest, policy, contextWindow, maxOutputTokens, toolCalling, vision);
    }

    public static final class Builder {
        private final SpringAiLlmService service;
        private final UnaryOperator<Prompt> finalRequest;
        private final ModelRequestPolicy policy;
        private final int contextWindow;
        private final @Nullable Integer maxOutputTokens;
        private final boolean toolCalling;
        private final boolean vision;
        private UnaryOperator<Prompt> requiredTools = UnaryOperator.identity();
        private BiFunction<SpringAiLlmService, ModelSampling, SpringAiLlmService> sampling = (llmService, ignored) -> llmService;
        private Predicate<Throwable> credentialRejection = failure -> false;

        private Builder(SpringAiLlmService service, UnaryOperator<Prompt> finalRequest, ModelRequestPolicy policy,
                        int contextWindow, @Nullable Integer maxOutputTokens, boolean toolCalling, boolean vision) {
            this.service = service;
            this.finalRequest = finalRequest;
            this.policy = policy;
            this.contextWindow = contextWindow;
            this.maxOutputTokens = maxOutputTokens;
            this.toolCalling = toolCalling;
            this.vision = vision;
        }

        public Builder requiredTools(UnaryOperator<Prompt> value) { requiredTools = value; return this; }
        public Builder sampling(BiFunction<SpringAiLlmService, ModelSampling, SpringAiLlmService> value) { sampling = value; return this; }
        public Builder credentialRejection(Predicate<Throwable> value) { credentialRejection = value; return this; }

        public ModelBinding build() {
            return new ModelBinding(service, finalRequest, policy, contextWindow, maxOutputTokens, toolCalling, vision,
                    requiredTools, sampling, credentialRejection, null);
        }
    }

    /**
     * This turn's output bound, creativity and reasoning level. The sampling values wrap the options converter, which
     * runs per inference and can still tell a helper call from an answer, so the cached client and its lease are
     * untouched and helper calls keep their own low effort.
     */
    /**
     * The request with only the named tool offered and a tool call required, so the model must call exactly that tool
     * (MEM-195 grounded turns search first). A request that does not offer the tool is left unchanged.
     */
    public UnaryOperator<Prompt> requireTool(String name) {
        return prompt -> {
            if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
                    || options.getToolCallbacks() == null) return prompt;
            var kept = options.getToolCallbacks().stream()
                    .filter(callback -> callback.getToolDefinition().name().equals(name)).toList();
            if (kept.isEmpty()) return prompt;
            return requiredTools.apply(new Prompt(prompt.getInstructions(), options.mutate().toolCallbacks(kept).build()));
        };
    }

    public ModelBinding forOptions(ModelSampling turnSampling, @Nullable Integer outputTokenLimit) {
        return new ModelBinding(
                turnSampling.isEmpty() ? service : sampling.apply(service, turnSampling),
                finalRequest, policy, contextWindow,
                outputTokenLimit == null ? maxOutputTokens : Integer.valueOf(outputAtMost(outputTokenLimit)),
                toolCalling, vision, requiredTools, sampling, credentialRejection, taskEffort);
    }

    /**
     * This binding for a task beside the conversation (naming, the question check, the minutes, corrections) at the
     * level chosen for it. {@link ReasoningEffort#OFF} keeps the helper path, thinking off; any other level keeps
     * thinking on and asks for that level, which a level named in the model's configuration still outranks.
     */
    public ModelBinding forTask(ReasoningEffort effort) {
        var base = effort == ReasoningEffort.OFF ? this : forOptions(new ModelSampling(null, effort, false), null);
        return new ModelBinding(base.service, finalRequest, policy, contextWindow, maxOutputTokens, toolCalling, vision,
                requiredTools, sampling, credentialRejection, effort);
    }

    /** Whether a task call through this binding thinks rather than running as a helper. */
    public boolean reasons() {
        return taskEffort != null && taskEffort != ReasoningEffort.OFF;
    }

    /** Whether a failure of a call through this binding means the provider refused its credential (the adapter's rule). */
    public boolean credentialRejected(Throwable failure) {
        return credentialRejection.test(failure);
    }

    public ModelBinding {
        Objects.requireNonNull(service);
        Objects.requireNonNull(finalRequest);
        Objects.requireNonNull(requiredTools);
        Objects.requireNonNull(sampling);
        Objects.requireNonNull(credentialRejection);
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
