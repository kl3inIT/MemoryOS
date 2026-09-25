package io.memoryos.ai;

import com.embabel.agent.api.common.ExecutingOperationContext;
import com.embabel.agent.core.AgentProcessRepository;
import com.embabel.agent.core.Budget;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Single model calls outside any conversation, for background work such as a meeting's minutes: no tools, no
 * attachments, no streaming. The deployment's token and cost budgets bound each call as they bound a Chat turn.
 */
public final class ModelCalls {
    private final ObjectProvider<ExecutingOperationContext> contexts;
    private final AgentProcessRepository processes;
    private final double costCap;
    private final int tokenCap;

    public ModelCalls(ObjectProvider<ExecutingOperationContext> contexts, AgentProcessRepository processes,
                      double costCap, int tokenCap) {
        this.contexts = contexts;
        this.processes = processes;
        this.costCap = costCap;
        this.tokenCap = tokenCap;
    }

    /**
     * One structured call: the model answers as {@code shape}, and {@code accounting} receives its usage even when the
     * call fails. The input is untrusted data; the caller's instructions say so.
     */
    public <T> T generateObject(ModelBinding selected, String instructions, String input, Class<T> shape,
                                Duration timeout, int maxOutputTokens, Consumer<ModelAccounting> accounting) {
        var context = contexts.getObject();
        var process = context.getProcessContext().getAgentProcess();
        var deadline = Instant.now().plus(timeout);
        ModelGuard admitted = null;
        try {
            var metadata = selected.service();
            int output = selected.outputAtMost(maxOutputTokens);
            var guard = new ModelGuard(metadata.getChatModel(), process, metadata,
                    new Budget(costCap, Integer.MAX_VALUE, tokenCap), 1,
                    () -> { if (!Instant.now().isBefore(deadline)) throw new IllegalStateException("CHAT_DEADLINE"); },
                    selected.policy(), selected.contextWindow() - output, selected.finalRequest());
            guard.outputLimit(output);
            admitted = guard;
            var runner = context.ai().withLlmService(selected.withModel(guard));
            var llm = Objects.requireNonNull(runner.getLlm()).withoutThinking().withMaxTokens(output).withTimeout(timeout);
            return runner.withLlm(llm).createObject(instructions + "\n\n" + input, shape);
        } finally {
            try { accounting.accept(admitted == null ? ModelAccounting.NONE : ModelAccounting.of(List.of(admitted), process, selected.service())); }
            finally { processes.delete(process); }
        }
    }
}
