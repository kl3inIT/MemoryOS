package io.memoryos.chat.execution;

import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import com.knuddels.jtokkit.api.EncodingType;

/** A configured native model and its provider-specific final-request policy. No run state or client cache. */
public record ChatModelBinding(SpringAiLlmService service, UnaryOperator<Prompt> finalRequest,
                               TokenCountEstimator tokens, int contextWindow, int maxOutputTokens) {
    private static final TokenCountEstimator DEFAULT_TOKENS = new JTokkitTokenCountEstimator(EncodingType.O200K_BASE);
    public ChatModelBinding(SpringAiLlmService service, UnaryOperator<Prompt> finalRequest) {
        this(service, finalRequest, DEFAULT_TOKENS, 32000, 4096);
    }
    public ChatModelBinding {
        Objects.requireNonNull(service);
        Objects.requireNonNull(finalRequest);
        Objects.requireNonNull(tokens);
        if (maxOutputTokens < 1 || contextWindow <= maxOutputTokens) throw new IllegalArgumentException("Invalid model limits");
    }

    public SpringAiLlmService withModel(ChatModel model) {
        return new SpringAiLlmService(service.getName(), service.getProvider(), model, service.getOptionsConverter(),
                service.getKnowledgeCutoffDate(), service.getPromptContributors(), service.getPricingModel(),
                service.supportsThinking(), service.getToolResponseContentAdapter(),
                service.getNativeStructuredOutputConfigurer(), service.getNativeSupport());
    }
}
