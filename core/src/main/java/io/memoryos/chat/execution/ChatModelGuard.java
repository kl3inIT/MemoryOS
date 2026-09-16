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
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

/** Per-turn integration of native streaming with accounting and final-cycle policy. */
@NullMarked
public final class ChatModelGuard implements ChatModel {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ChatModelGuard.class);
    private final ChatModel delegate;
    private final AgentProcess process;
    private final LlmMetadata model;
    private final Budget budget;
    private final int cycles;
    private final Runnable checkActive;
    private final UnaryOperator<Prompt> finalRequest;
    private final ChatRequestPolicy policy;
    private final ChatAdmissionLedger ledger;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger accounted = new AtomicInteger();
    private final AtomicInteger synchronousCalls = new AtomicInteger();
    private final AtomicInteger synchronousAccounted = new AtomicInteger();
    private final int inputLimit;
    private volatile int lastStreamInput;
    private @Nullable Scheduler scheduler;
    private BooleanSupplier hasEvidence = () -> false;
    private int synchronousLimit;
    private int outputLimit;
    private boolean webSiteFilter = true;
    private boolean researchPrompts;
    private UnaryOperator<Prompt> toolChoice = UnaryOperator.identity();
    public void webSiteFilter(boolean supported) { webSiteFilter = supported; }

    public ChatModelGuard(ChatModel delegate, AgentProcess process, LlmMetadata model, Budget budget,
            int cycles, Runnable checkActive, ChatRequestPolicy policy, int inputLimit, UnaryOperator<Prompt> finalRequest) {
        this(delegate, process, model, budget, cycles, checkActive, policy, inputLimit, finalRequest, new ChatAdmissionLedger());
    }

    public ChatModelGuard(ChatModel delegate, AgentProcess process, LlmMetadata model, Budget budget,
            int cycles, Runnable checkActive, ChatRequestPolicy policy, int inputLimit, UnaryOperator<Prompt> finalRequest,
            ChatAdmissionLedger ledger) {
        this.ledger = ledger;
        this.delegate = delegate;
        this.process = process;
        this.model = model;
        this.budget = budget;
        this.cycles = cycles;
        this.synchronousLimit = cycles;
        this.checkActive = checkActive;
        this.finalRequest = finalRequest;
        this.policy = policy;
        this.inputLimit = inputLimit;
    }

    public void checkActive() {
        checkActive.run();
        if (budget.earlyTerminationPolicy().shouldTerminate(process) != null)
            throw new IllegalStateException("CHAT_BUDGET_EXCEEDED");
    }

    public boolean usageKnown() {
        return calls.get() > 0 && accounted.get() == calls.get() && synchronousAccounted.get() == synchronousCalls.get();
    }

    /** Whether this guard admitted any model call; an unused guard contributes no usage. */
    public boolean used() {
        return calls.get() > 0 || synchronousCalls.get() > 0;
    }


    public int availableContextTokens() { return Math.max(0, inputLimit - lastStreamInput - 1024); }

    public void executionScheduler(Scheduler value) { this.scheduler = value; }

    public void evidenceAvailable(BooleanSupplier value) { this.hasEvidence = value; }

    public void synchronousLimit(int value) {
        if (value < 1) throw new IllegalArgumentException("Invalid helper inference limit");
        this.synchronousLimit = value;
    }

    public void outputLimit(int value) { this.outputLimit = value; }

    /**
     * Deep research composes every prompt itself, as Onyx does: no Chat tool guidance, citation or last-cycle reminder,
     * no final-cycle request rewrite. { cycles} stays a hard bound ({ CHAT_CYCLE_LIMIT}).
     */
    public void researchPrompts() { this.researchPrompts = true; }

    /** Request transform for the next inferences, for example the binding's required tool choice on research cycles. */
    public synchronized void toolChoice(UnaryOperator<Prompt> value) { this.toolChoice = value; }

    @Override
    public ChatResponse call(Prompt prompt) {
        // Native typed output records its own usage. Track completeness without recording it twice.
        io.memoryos.retrieval.SearchTasks.checkNativeActive();
        checkActive();
        var request = policy.options().apply(prompt);
        int input = policy.inputTokens(request, inputLimit);
        var reservation = admitHelper(input);
        var response = delegate.call(request);
        settle(reservation, response);
        policy.response().accept(response);
        if (response.getMetadata().getUsage().getTotalTokens() > 0) synchronousAccounted.incrementAndGet();
        // Return to the native caller first so usage is recorded even if cancellation arrived during IO.
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt original) {
        return Flux.defer(() -> {
            checkActive();
            var admission = admitStream(original);
            boolean lastCycle = admission.lastCycle();
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
                        policy.response().accept(response);
                        if (response.getResult() != null) {
                            String reason = response.getResult().getMetadata().getFinishReason();
                            if (reason != null && !reason.isBlank())
                                finished.set(true);
                            if ("length".equalsIgnoreCase(reason) && !response.getResult().getOutput().getToolCalls().isEmpty())
                                throw new IllegalStateException("CHAT_INCOMPLETE_RESPONSE");
                            if (lastCycle && !response.getResult().getOutput().getToolCalls().isEmpty())
                                throw new IllegalStateException("CHAT_LAST_CYCLE_TOOL_CALL");
                        }
                    })
                    .concatWith(Flux.defer(() -> finished.get() ? Flux.<ChatResponse>empty()
                            : Flux.error(new IllegalStateException("CHAT_INCOMPLETE_RESPONSE"))))
                    .doOnComplete(record).doOnError(ignored -> record.run()).doOnCancel(record);
        });
    }


    private ChatAdmissionLedger.Reservation reserve(int input) {
        return ledger.reserve(budget, model.getPricingModel(), input, outputLimit);
    }

    private void settle(ChatAdmissionLedger.Reservation reservation, ChatResponse response) {
        ledger.settle(reservation, model.getPricingModel(), response.getMetadata().getUsage());
    }

    private record StreamAdmission(boolean lastCycle, Prompt request, ChatAdmissionLedger.Reservation reservation) {}

    private synchronized StreamAdmission admitStream(Prompt original) {
        int cycle = calls.get() + 1;
        if (cycle > cycles) throw new IllegalStateException("CHAT_CYCLE_LIMIT");
        boolean lastCycle = cycle == cycles && !researchPrompts;
        var guided = researchPrompts ? original : ChatPrompts.forInference(original, hasEvidence.getAsBoolean(), lastCycle, webSiteFilter);
        var request = policy.options().apply(toolChoice.apply(lastCycle ? finalRequest.apply(guided) : guided));
        int input = policy.inputTokens(request, inputLimit);
        var reservation = reserve(input);
        lastStreamInput = input;
        calls.incrementAndGet();
        return new StreamAdmission(lastCycle, request, reservation);
    }

    private synchronized ChatAdmissionLedger.Reservation admitHelper(int input) {
        if (synchronousCalls.get() >= synchronousLimit) throw new IllegalStateException("CHAT_CYCLE_LIMIT");
        var reservation = reserve(input);
        synchronousCalls.incrementAndGet();
        return reservation;
    }
}
