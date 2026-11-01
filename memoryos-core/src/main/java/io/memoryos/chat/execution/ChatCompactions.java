package io.memoryos.chat.execution;

import io.memoryos.BusinessException;
import io.memoryos.ai.ModelAccounting;
import io.memoryos.ai.ModelFlow;
import io.memoryos.ai.ModelRequestPolicy;
import io.memoryos.ai.ModelResolver;
import io.memoryos.ai.TurnFailureException;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatModelSelector;
import io.memoryos.chat.session.ChatTurnPersistence;
import io.memoryos.shared.ActorId;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageLimitException;
import io.memoryos.usage.AiUsageLimitService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MEM-268: summarises a conversation's earlier turns after an answer is saved, off the answer's path, in the process
 * that ran the turn (Onyx). One compaction a session at a time, by a claim in the database; a turn that starts before
 * the summary exists replays the previous one and the window, and nothing waits for it. A failure writes nothing,
 * keeps the window, and the session is not tried again for ten minutes (Hermes's cooldown). Nothing is shown.
 */
public final class ChatCompactions {
    private static final Logger LOG = LoggerFactory.getLogger(ChatCompactions.class);
    /** How long a claim holds the session; a holder that dies lets it lapse. */
    static final Duration HOLD = Duration.ofMinutes(5);
    /** How long a session waits after a failed compaction. */
    static final Duration COOLDOWN = Duration.ofMinutes(10);
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    /** Extra output a task model that reasons is given for its thinking. */
    private static final int REASONING_TOKENS = 8192;

    private final ChatTurnPersistence persistence;
    private final ChatModelSelector models;
    private final ChatModelExecutor model;
    private final @Nullable AiUsageLimitService spending;
    private final Executor executor;
    private final Semaphore running;
    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    /** {@code concurrency} bounds the compactions this process runs at once; past it, the next turn tries again. */
    public ChatCompactions(ChatTurnPersistence persistence, ChatModelSelector models, ChatModelExecutor model,
                           @Nullable AiUsageLimitService spending, Executor executor, int concurrency,
                           MeterRegistry meters, ObservationRegistry observations) {
        this.persistence = persistence;
        this.models = models;
        this.model = model;
        this.spending = spending;
        this.executor = executor;
        this.running = new Semaphore(Math.max(1, concurrency));
        this.meters = meters;
        this.observations = observations;
    }

    /**
     * After {@code assistant} was saved as a completed answer: compacts the session when what its next turn would
     * replay passed the threshold of {@code historyBudget}, counted with the conversation model's {@code policy}.
     */
    public void after(ActorId actor, UUID session, UUID assistant, ModelRequestPolicy policy, int historyBudget) {
        if (!running.tryAcquire()) return;
        try {
            executor.execute(() -> {
                try { compact(actor, session, assistant, policy, historyBudget); }
                finally { running.release(); }
            });
        } catch (RuntimeException rejected) {
            running.release();
        }
    }

    void compact(ActorId actor, UUID session, UUID assistant, ModelRequestPolicy policy, int historyBudget) {
        try {
            var input = persistence.compactionInput(actor, session, assistant).orElse(null);
            if (input == null || ChatCompaction.plan(input.newestFirst(), input.summary(), policy, historyBudget).isEmpty())
                return;
            if (!persistence.claimCompaction(input.tenant(), session, HOLD)) return;
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "chat.compaction.failed").addKeyValue("session_id", session)
                    .addKeyValue("error_type", failure.getClass().getName()).addKeyValue("error_code", code(failure))
                    .log("Chat compaction could not start");
            return;
        }
        var observation = Observation.createNotStarted("memoryos.chat.compaction", observations).start();
        @Nullable Duration retry = null;
        String outcome;
        try (var scope = observation.openScope()) {
            outcome = write(actor, session, assistant, policy, historyBudget);
        } catch (RuntimeException failure) {
            retry = COOLDOWN;
            outcome = "failed";
            observation.error(failure);
            LOG.atWarn().addKeyValue("event", "chat.compaction.failed").addKeyValue("session_id", session)
                    .addKeyValue("error_type", failure.getClass().getName()).addKeyValue("error_code", code(failure))
                    .log("Chat compaction failed; the window replays as before");
        } finally {
            try {
                persistence.releaseCompaction(session, retry);
            } catch (RuntimeException failure) {
                LOG.atWarn().addKeyValue("event", "chat.compaction.release_failed").addKeyValue("session_id", session)
                        .addKeyValue("error_type", failure.getClass().getName())
                        .log("Chat compaction claim not released; it lapses with its hold");
            }
        }
        observation.lowCardinalityKeyValue("outcome", outcome).stop();
        Counter.builder("memoryos.chat.compaction.runs").description("Compactions of a long conversation, by outcome")
                .tag("outcome", outcome).register(meters).increment();
    }

    /** Under the claim: reads the branch again, since a compaction that ended meanwhile moved the cutoff. */
    private String write(ActorId actor, UUID session, UUID assistant, ModelRequestPolicy policy, int historyBudget) {
        var input = persistence.compactionInput(actor, session, assistant).orElse(null);
        if (input == null) return "gone";
        var plan = ChatCompaction.plan(input.newestFirst(), input.summary(), policy, historyBudget).orElse(null);
        if (plan == null) return "current";
        // A spent budget leaves the window, as it leaves a conversation's short title.
        if (spending != null) {
            try { spending.enforce(actor); }
            catch (AiUsageLimitException refused) { return "budget"; }
        }
        try (var selected = models.resolveFlow(actor, session, ModelFlow.CHAT_COMPACTION)) {
            var binding = selected.binding();
            int output = binding.outputAtMost(summaryTokens(historyBudget) + (binding.reasons() ? REASONING_TOKENS : 0));
            var request = ChatCompaction.request(plan, binding.policy(), binding.inputLimit(output))
                    .orElseThrow(() -> ChatException.invalid("The messages to summarise exceed the summary model's input."));
            String summary = model.summarize(binding, request, output, TIMEOUT, accounting -> record(actor, selected, accounting));
            int tokens = policy.tokens().estimate(ChatTurnSetup.SUMMARY_LABEL + summary);
            persistence.saveSummary(input.tenant(), session, plan.parent(), plan.cutoff(), summary, tokens,
                    selected.modelConfigurationId());
            DistributionSummary.builder("memoryos.chat.compaction.summary.tokens")
                    .description("The tokens of a written summary, as the conversation model counts them")
                    .baseUnit("tokens").register(meters).record(tokens);
            return "written";
        }
    }

    /** The summary's output bound: an eighth of the conversation's history budget, between 1,024 and 8,192 tokens. */
    static int summaryTokens(int historyBudget) {
        return Math.clamp(historyBudget / 8, 1024, 8192);
    }

    private void record(ActorId actor, ModelResolver.Resolved selected, ModelAccounting accounting) {
        try {
            persistence.recordUsage(new ChatTurnPersistence.Usage(null, actor, AiUsageFlow.CHAT_COMPACTION,
                    selected.modelConfigurationId(), selected.provenance(), selected.binding().service().getName(), accounting));
        } catch (RuntimeException failure) {
            LOG.atWarn().addKeyValue("event", "chat.compaction.usage_not_recorded")
                    .addKeyValue("error_type", failure.getClass().getName()).log("Chat compaction usage not recorded");
        }
    }

    private static String code(RuntimeException failure) {
        if (failure instanceof BusinessException business) return business.code();
        if (failure instanceof TurnFailureException turn) return turn.code();
        return "CHAT_COMPACTION_FAILED";
    }
}
