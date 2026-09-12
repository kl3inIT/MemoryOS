package io.memoryos.chat.execution;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;

/** A configured native model and immutable request policy. No run state or client cache. */
public record ChatModelBinding(SpringAiLlmService service, UnaryOperator<Prompt> finalRequest,
                               ChatRequestPolicy policy, int contextWindow, int maxOutputTokens, boolean toolCalling, boolean vision) {
    public ChatModelBinding forOptions(io.memoryos.chat.ChatTurnOptions options) {
        return new ChatModelBinding(service, finalRequest, policy, contextWindow,
                options.outputTokenLimit() == null ? maxOutputTokens : Math.min(maxOutputTokens, options.outputTokenLimit()),
                toolCalling, vision);
    }
    public ChatModelBinding {
        Objects.requireNonNull(service);
        Objects.requireNonNull(finalRequest);
        Objects.requireNonNull(policy);
        if (maxOutputTokens < 1 || contextWindow <= maxOutputTokens) throw new IllegalArgumentException("Invalid model limits");
    }

    /** Contributions were frozen into the admitted system message before reserving this turn. */
    public SpringAiLlmService withModel(ChatModel model) {
        return new SpringAiLlmService(service.getName(), service.getProvider(), model, service.getOptionsConverter(),
                service.getKnowledgeCutoffDate(), List.of(), service.getPricingModel(),
                service.supportsThinking(), service.getToolResponseContentAdapter(),
                service.getNativeStructuredOutputConfigurer(), service.getNativeSupport());
    }
}
