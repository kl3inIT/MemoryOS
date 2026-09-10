package io.memoryos.chat.execution;

import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Budget;
import com.embabel.agent.core.LlmInvocation;
import com.embabel.agent.core.Usage;
import com.embabel.common.ai.model.LlmMetadata;
import io.memoryos.chat.prompts.ChatPrompts;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

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
    private final AtomicInteger synchronousCalls = new AtomicInteger();
    private final AtomicInteger synchronousAccounted = new AtomicInteger();
    private @Nullable TokenCountEstimator tokens;
    private int inputLimit = Integer.MAX_VALUE;
    private volatile int lastStreamInput;
    private @Nullable Scheduler scheduler;
    private BooleanSupplier hasEvidence = () -> false;
    private int synchronousLimit;
    private int outputLimit;
    private long admittedTokens;
    private double admittedCost;

    public ChatModelGuard(ChatModel delegate, AgentProcess process, LlmMetadata model, Budget budget,
            int cycles, Runnable checkActive, UnaryOperator<Prompt> finalRequest) {
        this.delegate = delegate;
        this.process = process;
        this.model = model;
        this.budget = budget;
        this.cycles = cycles;
        this.synchronousLimit = cycles;
        this.checkActive = checkActive;
        this.finalRequest = finalRequest;
    }

    public void checkActive() {
        checkActive.run();
        if (budget.earlyTerminationPolicy().shouldTerminate(process) != null)
            throw new IllegalStateException("CHAT_BUDGET_EXCEEDED");
    }

    public boolean usageKnown() {
        return calls.get() > 0 && accounted.get() == calls.get() && synchronousAccounted.get() == synchronousCalls.get();
    }

    public void contextLimit(TokenCountEstimator estimator, int limit) {
        this.tokens = estimator;
        this.inputLimit = limit;
    }

    public int availableContextTokens() { return Math.max(0, inputLimit - lastStreamInput - 1024); }

    public void executionScheduler(Scheduler value) { this.scheduler = value; }

    public void evidenceAvailable(BooleanSupplier value) { this.hasEvidence = value; }

    public void synchronousLimit(int value) {
        if (value < 1) throw new IllegalArgumentException("Invalid helper inference limit");
        this.synchronousLimit = value;
    }

    public void outputLimit(int value) { this.outputLimit = value; }

    @Override
    public ChatResponse call(Prompt prompt) {
        // Native typed output records its own usage. Track completeness without recording it twice.
        io.memoryos.retrieval.SearchTasks.checkNativeActive();
        checkActive();
        int input = validateContext(prompt);
        var reservation = admitHelper(input);
        var response = delegate.call(prompt);
        settle(reservation, response);
        if (response.getMetadata().getUsage().getTotalTokens() > 0) synchronousAccounted.incrementAndGet();
        // Return to the native caller first so usage is recorded even if cancellation arrived during IO.
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt original) {
        return Flux.defer(() -> {
            checkActive();
            var admission = admitStream(original);
            int cycle = admission.cycle();
            var request = admission.request();
            var reservation = admission.reservation();
            var finished = new AtomicBoolean();
            var usageResponse = new AtomicReference<@Nullable ChatResponse>();
            var recorded = new AtomicBoolean();
            var settled = new AtomicBoolean();
            Instant started = Instant.now();
            Runnable record = () -> {
                var response = usageResponse.get();
                if (response != null && settled.compareAndSet(false, true)) settle(reservation, response);
                if (response != null && recorded.compareAndSet(false, true)) {
                    var usage = response.getMetadata().getUsage();
                    process.recordLlmInvocation(new LlmInvocation(model,
                            new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getNativeUsage()),
                            null, started, Duration.between(started, Instant.now())));
                    accounted.incrementAndGet();
                }
            };
            var responses = delegate.stream(request);
            // Embabel invokes blocking tools synchronously from stream callbacks. Keep them off provider IO threads.
            if (scheduler != null) responses = responses.publishOn(scheduler, 1);
            return responses
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

    private int validateContext(Prompt prompt) {
        var estimator = tokens;
        if (estimator == null) return 0;
        int count = 64;
        for (var message : prompt.getInstructions()) {
            count += 32 + estimator.estimate(message.getText() == null ? "" : message.getText());
            if (message instanceof ToolResponseMessage tool) {
                for (var response : tool.getResponses()) count += estimator.estimate(response.responseData()) + 32;
            }
            if (message instanceof AssistantMessage assistant) {
                for (var tool : assistant.getToolCalls()) count += estimator.estimate(tool.arguments()) + 32;
            }
        }
        if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null) {
            for (var callback : options.getToolCallbacks()) {
                var definition = callback.getToolDefinition();
                count += estimator.estimate(definition.description() + definition.inputSchema()) + 32;
            }
        }
        if (count > inputLimit) throw new IllegalStateException("CHAT_CONTEXT_LIMIT");
        return count;
    }

    /** Reserve before IO so concurrent helpers cannot all spend the same remaining budget.
     * These are admission bounds only. Embabel remains the sole invocation/usage/cost ledger. */
    private synchronized Reservation reserve(int input) {
        long tokens = (long) input + outputLimit;
        var pricing = model.getPricingModel();
        double cost = pricing == null ? 0 : pricing.costOf(input, outputLimit);
        if (admittedTokens + tokens > budget.getTokens() || admittedCost + cost > budget.getCost())
            throw new IllegalStateException("CHAT_BUDGET_EXCEEDED");
        admittedTokens += tokens;
        admittedCost += cost;
        return new Reservation(tokens, cost);
    }

    private synchronized void settle(Reservation reservation, ChatResponse response) {
        var usage = response.getMetadata().getUsage();
        // Unknown/failed requests retain their allowance: never turn unreported usage into free budget.
        if (usage.getTotalTokens() <= 0) return;
        admittedTokens += usage.getTotalTokens() - reservation.tokens();
        var pricing = model.getPricingModel();
        if (pricing != null) admittedCost += pricing.costOf(usage.getPromptTokens(), usage.getCompletionTokens()) - reservation.cost();
    }

    private record Reservation(long tokens, double cost) {}

    private record StreamAdmission(int cycle, Prompt request, Reservation reservation) {}

    private synchronized StreamAdmission admitStream(Prompt original) {
        int cycle = calls.get() + 1;
        if (cycle > cycles) throw new IllegalStateException("CHAT_CYCLE_LIMIT");
        var guided = ChatPrompts.forInference(original, hasEvidence.getAsBoolean(), cycle == cycles);
        var request = cycle == cycles ? finalRequest.apply(guided) : guided;
        int input = validateContext(request);
        var reservation = reserve(input);
        lastStreamInput = input;
        calls.incrementAndGet();
        return new StreamAdmission(cycle, request, reservation);
    }

    private synchronized Reservation admitHelper(int input) {
        if (synchronousCalls.get() >= synchronousLimit) throw new IllegalStateException("CHAT_CYCLE_LIMIT");
        var reservation = reserve(input);
        synchronousCalls.incrementAndGet();
        return reservation;
    }
}
