package io.memoryos.ai;

import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Budget;
import com.embabel.agent.core.LlmInvocation;
import com.embabel.agent.core.Usage;
import com.embabel.common.ai.model.LlmMetadata;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

/**
 * Admission, budget and usage accounting around one model for one piece of work: a Chat turn, a research agent or a
 * single call outside any conversation. The work that owns it may shape each streamed inference's prompt through
 * {@link #guide}.
 */
@NullMarked
public class ModelGuard implements ChatModel {
    private static final Logger LOG = LoggerFactory.getLogger(ModelGuard.class);
    private final ChatModel delegate;
    private final AgentProcess process;
    private final LlmMetadata model;
    private final Budget budget;
    private final int cycles;
    private final Runnable checkActive;
    private final UnaryOperator<Prompt> finalRequest;
    private final ModelRequestPolicy policy;
    private final ModelAdmissionLedger ledger;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger accounted = new AtomicInteger();
    private final AtomicInteger synchronousCalls = new AtomicInteger();
    private final AtomicInteger synchronousAccounted = new AtomicInteger();
    private final AtomicLong cacheRead = new AtomicLong();
    private final AtomicLong cacheWrite = new AtomicLong();
    private final int inputLimit;
    private volatile int lastStreamInput;
    private @Nullable Scheduler scheduler;
    private int synchronousLimit;
    private int outputLimit;
    private boolean researchPrompts;
    private UnaryOperator<Prompt> toolChoice = UnaryOperator.identity();
    private UnaryOperator<Prompt> firstCycle = UnaryOperator.identity();

    public ModelGuard(ChatModel delegate, AgentProcess process, LlmMetadata model, Budget budget,
            int cycles, Runnable checkActive, ModelRequestPolicy policy, int inputLimit, UnaryOperator<Prompt> finalRequest) {
        this(delegate, process, model, budget, cycles, checkActive, policy, inputLimit, finalRequest, new ModelAdmissionLedger());
    }

    public ModelGuard(ChatModel delegate, AgentProcess process, LlmMetadata model, Budget budget,
            int cycles, Runnable checkActive, ModelRequestPolicy policy, int inputLimit, UnaryOperator<Prompt> finalRequest,
            ModelAdmissionLedger ledger) {
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
            throw TurnFailure.BUDGET_EXCEEDED.exception();
    }

    /**
     * Whether every admitted call, streamed or synchronous, reported its usage. A guard used only for synchronous calls
     * (the guardrail check, meeting minutes, transcript corrections) is known once they reported; requiring a streamed
     * call recorded every such call as unknown.
     */
    public boolean usageKnown() {
        return used() && accounted.get() == calls.get() && synchronousAccounted.get() == synchronousCalls.get();
    }

    /** Whether at least one admitted call reported its usage, so part of this guard's work has known totals. */
    public boolean usageReported() {
        return accounted.get() > 0 || synchronousAccounted.get() > 0;
    }

    /** Whether this guard admitted any model call; an unused guard contributes no usage. */
    public boolean used() {
        return calls.get() > 0 || synchronousCalls.get() > 0;
    }


    public int availableContextTokens() { return Math.max(0, inputLimit - lastStreamInput - 1024); }

    /** The estimated input of the latest streamed call, or null before the first one: what the context holds. */
    public @Nullable Integer contextTokens() { return lastStreamInput > 0 ? lastStreamInput : null; }

    /** The model's input limit this guard admits calls against. */
    public int inputLimit() { return inputLimit; }

    public void executionScheduler(Scheduler value) { this.scheduler = value; }

    /** Onyx has no helper-call count: tool work is bounded by the cycle limit and the turn's budgets. */
    public static final int UNBOUNDED_HELPERS = Integer.MAX_VALUE;

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

    /** Request transform for the first streamed inference only, for example forcing one named tool (MEM-195). */
    public synchronized void firstCycle(UnaryOperator<Prompt> value) { this.firstCycle = value; }

    @Override
    public ChatResponse call(Prompt prompt) {
        // Native typed output records its own usage. Track completeness without recording it twice.
        checkHelperActive();
        checkActive();
        var request = policy.options().apply(prompt);
        int input = policy.inputTokens(request, inputLimit);
        var reservation = admitHelper(input);
        var response = delegate.call(request);
        settle(reservation, response, request, false);
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
                if (response != null && settled.compareAndSet(false, true)) settle(reservation, response, request, true);
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
                                throw TurnFailure.INCOMPLETE_RESPONSE.exception();
                            if (lastCycle && !response.getResult().getOutput().getToolCalls().isEmpty())
                                throw TurnFailure.LAST_CYCLE_TOOL_CALL.exception();
                        }
                    })
                    .concatWith(Flux.defer(() -> finished.get() ? Flux.empty()
                            : Flux.error(TurnFailure.INCOMPLETE_RESPONSE.exception())))
                    .doOnComplete(record).doOnError(ignored -> record.run()).doOnCancel(record);
        });
    }


    /** The prompt one streamed inference sends; {@code lastCycle} is the inference that may no longer call tools. */
    protected Prompt guide(Prompt original, boolean lastCycle) {
        return original;
    }

    /** Runs before a synchronous helper inference, so work that owns the guard can stop it first. */
    protected void checkHelperActive() {
    }

    private ModelAdmissionLedger.Reservation reserve(int input) {
        return ledger.reserve(budget, model.getPricingModel(), input, outputLimit);
    }

    private void settle(ModelAdmissionLedger.Reservation reservation, ChatResponse response, Prompt request, boolean streamed) {
        var usage = response.getMetadata().getUsage();
        ledger.settle(reservation, model.getPricingModel(), usage);
        Long cached = usage.getCacheReadInputTokens();
        if (cached != null && cached > 0) cacheRead.addAndGet(cached);
        Long written = usage.getCacheWriteInputTokens();
        if (written != null && written > 0) cacheWrite.addAndGet(written);
        if (usage.getTotalTokens() > 0 && LOG.isInfoEnabled()) {
            var tools = request.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null
                    ? options.getToolCallbacks() : List.<ToolCallback>of();
            var toolText = new StringBuilder();
            for (var tool : tools) {
                var definition = tool.getToolDefinition();
                toolText.append(definition.name()).append('\n').append(definition.description()).append('\n')
                        .append(definition.inputSchema()).append('\n');
            }
            var systemText = new StringBuilder();
            for (var message : request.getInstructions())
                if (message.getMessageType() == MessageType.SYSTEM) systemText.append(message.getText()).append('\n');
            // Fingerprints, never content: a miss whose tools and system prompt match the call before points at the
            // history or the provider (MEM-269).
            LOG.atInfo().addKeyValue("event", "ai.model.call.completed")
                    .addKeyValue("model", model.getName())
                    .addKeyValue("streamed", streamed)
                    .addKeyValue("input_tokens", usage.getPromptTokens())
                    .addKeyValue("cache_read_tokens", cached == null ? 0 : cached)
                    .addKeyValue("cache_write_tokens", written == null ? 0 : written)
                    .addKeyValue("output_tokens", usage.getCompletionTokens())
                    .addKeyValue("tools", tools.size())
                    .addKeyValue("tools_fingerprint", fingerprint(toolText))
                    .addKeyValue("system_fingerprint", fingerprint(systemText))
                    .addKeyValue("messages", request.getInstructions().size())
                    .log("Model call completed");
        }
    }

    /** The first twelve hex digits of the text's SHA-256: enough to tell two prompts apart, nothing of their content. */
    static String fingerprint(CharSequence text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Input tokens the provider served from its prompt cache, as reported by Spring AI's portable usage. */
    public long cacheReadTokens() { return cacheRead.get(); }

    /** Input tokens the provider wrote to its prompt cache, which Anthropic bills above the input rate. */
    public long cacheWriteTokens() { return cacheWrite.get(); }

    private record StreamAdmission(boolean lastCycle, Prompt request, ModelAdmissionLedger.Reservation reservation) {}

    private synchronized StreamAdmission admitStream(Prompt original) {
        int cycle = calls.get() + 1;
        if (cycle > cycles) throw TurnFailure.CYCLE_LIMIT.exception();
        boolean lastCycle = cycle == cycles && !researchPrompts;
        var guided = researchPrompts ? original : guide(original, lastCycle);
        if (cycle == 1 && !lastCycle) guided = firstCycle.apply(guided);
        var request = policy.options().apply(toolChoice.apply(lastCycle ? finalRequest.apply(guided) : guided));
        int input = policy.inputTokens(request, inputLimit);
        var reservation = reserve(input);
        lastStreamInput = input;
        calls.incrementAndGet();
        return new StreamAdmission(lastCycle, request, reservation);
    }

    private synchronized ModelAdmissionLedger.Reservation admitHelper(int input) {
        if (synchronousCalls.get() >= synchronousLimit) throw TurnFailure.CYCLE_LIMIT.exception();
        var reservation = reserve(input);
        synchronousCalls.incrementAndGet();
        return reservation;
    }
}
