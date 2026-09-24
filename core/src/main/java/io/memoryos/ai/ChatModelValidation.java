package io.memoryos.ai;

import com.embabel.common.ai.model.LlmOptions;
import org.springframework.beans.factory.annotation.Value;
import io.memoryos.shared.ActorId;
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
public final class ChatModelValidation {
    private final ChatModelResolver models;
    private final int maxOutputTokens;
    private final Semaphore permits = new Semaphore(2);
    /** Declared so the provider validates tools beside this entry's options; tool_choice=none keeps it uncalled. */
    private static final org.springframework.ai.tool.ToolCallback PROBE_TOOL = new org.springframework.ai.tool.ToolCallback() {
        @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder().name("connection_probe")
                    .description("Never called; present only to validate tool support.")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
        }
        @Override public String call(String toolInput) { return "{}"; }
    };
    ChatModelValidation(ChatModelResolver models, @Value("${memoryos.chat.execution.max-output-tokens}") int maxOutputTokens) {
        this.models = models;
        this.maxOutputTokens = maxOutputTokens;
    }
    public record Result(boolean reachable, @Nullable String failureCode) {}

    public Result validate(ActorId actor, UUID id) {
        // Authorization precedes admission and provider I/O; it never becomes a provider error response.
        try (var resolved = models.forValidation(actor, id)) {
            if (!permits.tryAcquire()) throw AiException.busy();
            try {
                var binding = resolved.binding();
                int output = binding.outputAtMost(Math.min(32, maxOutputTokens));
                var options = binding.service().convertOptions(new LlmOptions().withMaxTokens(output));
                // A model that declares tool calling is only usable if the provider accepts tools next to
                // this entry's reasoning options, which some model families reject; probe that combination
                // here so the rejection reaches the administrator instead of the first tool-bearing turn.
                if (binding.toolCalling() && options instanceof org.springframework.ai.openai.OpenAiChatOptions openAi)
                    options = openAi.mutate().toolCallbacks(List.of(PROBE_TOOL)).toolChoice("none").build();
                var prompt = binding.policy().request(binding.finalRequest().apply(
                        new Prompt(List.of(new UserMessage("Reply OK.")), options)),
                        binding.contextWindow() - output);
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
