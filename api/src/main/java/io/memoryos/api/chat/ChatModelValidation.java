package io.memoryos.api.chat;

import com.embabel.common.ai.model.LlmOptions;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ChatModelResolver;
import io.memoryos.chat.execution.ChatExecutionProperties;
import io.memoryos.iam.ActorId;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

@Component
final class ChatModelValidation {
    private final ChatModelResolver models;
    private final ChatExecutionProperties limits;
    private final Semaphore permits = new Semaphore(2);
    ChatModelValidation(ChatModelResolver models, ChatExecutionProperties limits) { this.models = models; this.limits = limits; }
    record Result(boolean reachable, @Nullable String failureCode) {}

    Result validate(ActorId actor, UUID id) {
        // Authorization precedes admission and provider I/O; it never becomes a provider error response.
        try (var resolved = models.forValidation(actor, id)) {
            if (!permits.tryAcquire()) throw ChatException.busy();
            try {
                var binding = resolved.binding();
                int output = Math.min(32, Math.min(limits.maxOutputTokens(), binding.maxOutputTokens()));
                var options = binding.service().convertOptions(new LlmOptions().withMaxTokens(output));
                var prompt = binding.policy().request(binding.finalRequest().apply(
                        new Prompt(List.of(new UserMessage("Reply OK.")), options)),
                        Math.min(limits.contextTokenLimit(), binding.contextWindow() - output));
                var finished = new AtomicBoolean();
                binding.service().getChatModel().stream(prompt).doOnNext(response -> {
                    binding.policy().response().accept(response);
                    if (response.getResult() != null && response.getResult().getMetadata().getFinishReason() != null
                            && !response.getResult().getMetadata().getFinishReason().isBlank()) finished.set(true);
                }).then().block(Duration.ofSeconds(15));
                boolean complete = finished.get();
                return new Result(complete, complete ? null : "INCOMPLETE_RESPONSE");
            } catch (RuntimeException failure) {
                return new Result(false, "PROVIDER_UNAVAILABLE");
            } finally { permits.release(); }
        }
    }
}
