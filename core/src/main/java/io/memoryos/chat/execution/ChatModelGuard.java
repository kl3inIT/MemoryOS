package io.memoryos.chat.execution;

import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Budget;
import com.embabel.agent.core.LlmInvocation;
import com.embabel.agent.core.Usage;
import com.embabel.common.ai.model.LlmMetadata;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Per-turn integration of native streaming with accounting and final-cycle policy. */
@NullMarked
public final class ChatModelGuard implements ChatModel {
    private final ChatModel delegate;
    private final AgentProcess process;
    private final LlmMetadata model;
    private final Budget budget;
    private final int cycles;
    private final Runnable checkActive;
    private final UnaryOperator<Prompt> finalRequest;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger accounted = new AtomicInteger();

    public ChatModelGuard(ChatModel delegate, AgentProcess process, LlmMetadata model, Budget budget,
            int cycles, Runnable checkActive, UnaryOperator<Prompt> finalRequest) {
        this.delegate = delegate;
        this.process = process;
        this.model = model;
        this.budget = budget;
        this.cycles = cycles;
        this.checkActive = checkActive;
        this.finalRequest = finalRequest;
    }

    public void checkActive() {
        checkActive.run();
        if (budget.earlyTerminationPolicy().shouldTerminate(process) != null)
            throw new IllegalStateException("CHAT_BUDGET_EXCEEDED");
    }

    public boolean usageKnown() { return calls.get() > 0 && accounted.get() == calls.get(); }

    @Override
    public ChatResponse call(Prompt prompt) {
        // Synchronous callers use native accounting; this integration owns streaming only.
        checkActive();
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt original) {
        return Flux.defer(() -> {
            checkActive();
            int previous = calls.getAndUpdate(count -> count < cycles ? count + 1 : count);
            if (previous >= cycles) return Flux.error(new IllegalStateException("CHAT_CYCLE_LIMIT"));
            int cycle = previous + 1;
            var request = cycle == cycles ? finalRequest.apply(original) : original;
            var finished = new AtomicBoolean();
            var usageResponse = new AtomicReference<@Nullable ChatResponse>();
            var recorded = new AtomicBoolean();
            Instant started = Instant.now();
            Runnable record = () -> {
                var response = usageResponse.get();
                if (response != null && recorded.compareAndSet(false, true)) {
                    var usage = response.getMetadata().getUsage();
                    process.recordLlmInvocation(new LlmInvocation(model,
                            new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getNativeUsage()),
                            null, started, Duration.between(started, Instant.now())));
                    accounted.incrementAndGet();
                }
            };
            return delegate.stream(request)
                    .doOnNext(response -> {
                        var usage = response.getMetadata().getUsage();
                        if (usage.getTotalTokens() > 0) usageResponse.set(response);
                        if (response.getResult() != null) {
                            String reason = response.getResult().getMetadata().getFinishReason();
                            if (reason != null && !reason.isBlank())
                                finished.set(true);
                            if ("length".equalsIgnoreCase(reason) && !response.getResult().getOutput().getToolCalls().isEmpty())
                                throw new IllegalStateException("CHAT_INCOMPLETE_RESPONSE");
                            if (cycle == cycles && !response.getResult().getOutput().getToolCalls().isEmpty())
                                throw new IllegalStateException("CHAT_LAST_CYCLE_TOOL_CALL");
                        }
                    })
                    .concatWith(Flux.defer(() -> finished.get() ? Flux.empty()
                            : Flux.error(new IllegalStateException("CHAT_INCOMPLETE_RESPONSE"))))
                    .doOnComplete(record).doOnError(ignored -> record.run()).doOnCancel(record);
        });
    }
}
