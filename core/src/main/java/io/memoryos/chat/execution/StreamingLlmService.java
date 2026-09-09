package io.memoryos.chat.execution;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.loop.LlmMessageSender;
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.common.ai.model.PricingModel;
import com.embabel.common.ai.prompt.PromptContributor;

import java.time.LocalDate;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Capability binding for the verified OpenAI Chat Completions path; all execution stays native.
 */
@NullMarked
public record StreamingLlmService(SpringAiLlmService delegate) implements LlmService<StreamingLlmService> {
    public String getName() {
        return delegate.getName();
    }

    public String getProvider() {
        return delegate.getProvider();
    }

    public @Nullable LocalDate getKnowledgeCutoffDate() {
        return delegate.getKnowledgeCutoffDate();
    }

    public @Nullable PricingModel getPricingModel() {
        return delegate.getPricingModel();
    }

    public List<PromptContributor> getPromptContributors() {
        return delegate.getPromptContributors();
    }

    public LlmMessageSender createMessageSender(LlmOptions options) {
        return delegate.createMessageSender(options);
    }

    public LlmMessageStreamer createMessageStreamer(LlmOptions options) {
        return delegate.createMessageStreamer(options);
    }

    public boolean supportsStreaming() {
        return true;
    }

    public boolean supportsThinking() {
        return delegate.supportsThinking();
    }

    public StreamingLlmService withKnowledgeCutoffDate(LocalDate date) {
        return new StreamingLlmService(delegate.withKnowledgeCutoffDate(date));
    }

    public StreamingLlmService withPromptContributor(PromptContributor contributor) {
        return new StreamingLlmService(delegate.withPromptContributor(contributor));
    }
}
