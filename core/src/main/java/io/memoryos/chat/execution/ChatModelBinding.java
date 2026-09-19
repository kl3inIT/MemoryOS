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
