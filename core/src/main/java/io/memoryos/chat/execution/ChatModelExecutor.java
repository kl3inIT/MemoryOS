package io.memoryos.chat.execution;

import com.embabel.agent.api.common.ExecutingOperationContext;
import com.embabel.agent.api.streaming.StreamingPromptRunnerBuilder;
import com.embabel.agent.core.AgentProcessRepository;
import com.embabel.agent.core.Budget;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

/** Calls the public native runner. There is no MemoryOS inference/tool loop here. */
public final class ChatModelExecutor {
    private final ObjectProvider<ExecutingOperationContext> contexts;
    private final AgentProcessRepository processes;
    private final ChatExecutionProperties limits;

    public ChatModelExecutor(ObjectProvider<ExecutingOperationContext> contexts, AgentProcessRepository processes,
            ChatExecutionProperties limits) {
        this.contexts = contexts;
        this.processes = processes;
        this.limits = limits;
    }

    public record Accounting(@Nullable Long input, @Nullable Long output, @Nullable Double cost) {}

    public void execute(ChatTurnSetup setup, Runnable checkActive, Mono<?> cancellation,
            Consumer<String> output, Consumer<Accounting> accounting) {
        var selected = setup.binding();
        var metadata = selected.service();
        if (!metadata.getName().equals(setup.model())) throw new IllegalArgumentException("CHAT_MODEL_UNAVAILABLE");
        var context = contexts.getObject();
        var process = context.getProcessContext().getAgentProcess();
        var guard = new ChatModelGuard(metadata.getChatModel(), process, metadata,
                new Budget(limits.costBudgetUsd(), Integer.MAX_VALUE, limits.tokenBudget()), limits.maxCycles(), checkActive,
                selected.finalRequest());
        try {
            var service = new StreamingLlmService(selected.withModel(guard));
            var runner = context.ai().withLlmService(service);
            runner = runner.withLlm(Objects.requireNonNull(runner.getLlm()).withMaxTokens(Math.min(limits.maxOutputTokens(), selected.maxOutputTokens())))
                    .withToolCallContext(Map.of("actor", setup.actor(), "tenant", setup.tenant(), "runId", setup.assistantMessageId()));
            Duration remaining = Duration.between(Instant.now(), setup.deadline());
            if (remaining.isNegative() || remaining.isZero()) throw new IllegalStateException("CHAT_DEADLINE");
            new StreamingPromptRunnerBuilder(runner).streaming().withMessages(setup.messages()).generateStream()
                    .takeUntilOther(cancellation).doOnNext(text -> { guard.checkActive(); output.accept(text); }).blockLast(remaining);
        } finally {
            var usage = process.usage();
            try {
                accounting.accept(new Accounting(guard.usageKnown() && usage.getPromptTokens() != null ? usage.getPromptTokens().longValue() : null,
                        guard.usageKnown() && usage.getCompletionTokens() != null ? usage.getCompletionTokens().longValue() : null,
                        guard.usageKnown() && metadata.getPricingModel() != null ? process.cost() : null));
            } finally { processes.delete(process); }
        }
    }
}
