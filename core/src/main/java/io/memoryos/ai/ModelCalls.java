package io.memoryos.ai;

import com.embabel.agent.api.common.ExecutingOperationContext;
import com.embabel.agent.core.AgentProcessRepository;
import com.embabel.agent.core.Budget;
import com.embabel.chat.SystemMessage;
import com.embabel.chat.UserMessage;
import com.embabel.common.ai.model.NativeStructuredOutputMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Single model calls outside any conversation, for background work such as a meeting's minutes: no tools, no
 * attachments, no streaming. The deployment's token and cost budgets bound each call as they bound a Chat turn.
 */
public final class ModelCalls {
    /** The least output a task that reasons is given, so its thinking does not cut a short answer off. */
    static final int REASONING_OUTPUT_TOKENS = 4096;
    private final ObjectProvider<ExecutingOperationContext> contexts;
    private final AgentProcessRepository processes;
    private final double costCap;
    private final int tokenCap;
    private final int attempts;

    /**
     * {@code attempts} is Embabel's data-binding {@code max-attempts}: the guard admits that many calls, so a reply
     * that does not bind is asked again instead of failing on the first malformed answer.
     */
    public ModelCalls(ObjectProvider<ExecutingOperationContext> contexts, AgentProcessRepository processes,
                      double costCap, int tokenCap, int attempts) {
        if (attempts < 1) throw new IllegalArgumentException("attempts must be positive");
        this.contexts = contexts;
        this.processes = processes;
        this.costCap = costCap;
        this.tokenCap = tokenCap;
        this.attempts = attempts;
    }

    /**
     * One structured call: the model answers as {@code shape}, and {@code accounting} receives its usage even when the
     * call fails. The instructions travel as the system message and the untrusted input as the user message, so text
     * inside the input cannot pose as the caller's instructions. A binding made {@link ModelBinding#forTask for a task}
     * at a level other than off lets the model think; any other call runs as a helper, with thinking off.
     */
    public <T> T generateObject(ModelBinding selected, String instructions, String input, Class<T> shape,
                                Duration timeout, int maxOutputTokens, Consumer<ModelAccounting> accounting) {
        return generateObject(selected, instructions, input, shape, timeout, maxOutputTokens, null, accounting);
    }

    /**
     * The same call at a sampling {@code temperature} of its own, such as 0 for a classifier that wants the most likely
     * label. The provider adapter sends it only to a model that takes one: a reasoning model, a model that rejects a
     * temperature and a model whose configuration names one keep their own.
     */
    public <T> T generateObject(ModelBinding selected, String instructions, String input, Class<T> shape,
                                Duration timeout, int maxOutputTokens, @Nullable Double temperature,
                                Consumer<ModelAccounting> accounting) {
        var context = contexts.getObject();
        var process = context.getProcessContext().getAgentProcess();
        var deadline = Instant.now().plus(timeout);
        ModelGuard admitted = null;
        try {
            var metadata = selected.service();
            // A task that reasons (ModelBinding.forTask) needs room for the reasoning as well as the answer.
            int output = selected.outputAtMost(selected.reasons() ? Math.max(maxOutputTokens, REASONING_OUTPUT_TOKENS) : maxOutputTokens);
            var guard = new ModelGuard(metadata.getChatModel(), process, metadata,
                    new Budget(costCap, Integer.MAX_VALUE, tokenCap), attempts,
                    () -> { if (!Instant.now().isBefore(deadline)) throw TurnFailure.DEADLINE.exception(); },
                    selected.policy(), selected.contextWindow() - output, selected.finalRequest());
            guard.outputLimit(output);
            admitted = guard;
            var runner = context.ai().withLlmService(selected.withModel(guard));
            var llm = Objects.requireNonNull(runner.getLlm()).withMaxTokens(output).withTimeout(timeout);
            if (!selected.reasons()) llm = llm.withoutThinking();
            if (temperature != null) llm = llm.withTemperature(temperature);
            // Said outright either way: left to Embabel, a type its own check doubts would quietly go back to
            // format instructions in the prompt.
            llm = (selected.structuredOutput() ? NativeStructuredOutputMode.ENABLED : NativeStructuredOutputMode.DISABLED)
                    .applyTo(llm);
            return runner.withLlm(llm).createObject(List.of(new SystemMessage(instructions), new UserMessage(input)), shape);
        } finally {
            try { accounting.accept(admitted == null ? ModelAccounting.NONE : ModelAccounting.of(List.of(admitted), process, selected.service())); }
            finally { processes.delete(process); }
        }
    }
}
