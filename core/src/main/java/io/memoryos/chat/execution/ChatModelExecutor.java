package io.memoryos.chat.execution;

import com.embabel.agent.api.common.ExecutingOperationContext;
import com.embabel.agent.api.streaming.StreamingPromptRunnerBuilder;
import com.embabel.agent.core.AgentProcessRepository;
import com.embabel.agent.core.Budget;
import com.embabel.agent.api.tool.Tool;
import io.memoryos.chat.ChatSearchEvent;
import io.memoryos.chat.tools.SearchTool;
import io.memoryos.chat.tools.ChatSearchProperties;
import io.memoryos.retrieval.DocumentSearchService;
import io.memoryos.retrieval.SearchTimings;
import java.util.ArrayList;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/** Calls the public native runner. There is no MemoryOS inference/tool loop here. */
public final class ChatModelExecutor {
    private final ObjectProvider<ExecutingOperationContext> contexts;
    private final AgentProcessRepository processes;
    private final ChatExecutionProperties limits;
    private final DocumentSearchService search;
    private final ChatSearchProperties searchLimits;
    private final Scheduler scheduler;
    private final SearchTimings timings;

    public ChatModelExecutor(ObjectProvider<ExecutingOperationContext> contexts, AgentProcessRepository processes,
            ChatExecutionProperties limits, DocumentSearchService search, ChatSearchProperties searchLimits, Scheduler scheduler, SearchTimings timings) {
        this.contexts = contexts;
        this.processes = processes;
        this.limits = limits;
        this.search = search;
        this.searchLimits = searchLimits;
        this.scheduler = scheduler;
        this.timings = timings;
    }

    public record Accounting(@Nullable Long input, @Nullable Long output, @Nullable Double cost) {}

    public void execute(ChatTurnSetup setup, Runnable checkActive, Mono<?> cancellation,
            Consumer<String> output, Consumer<Accounting> accounting, Consumer<ChatSearchEvent> events,
            Consumer<CompletableFuture<Void>> onDrained) {
        var selected = setup.binding();
        var metadata = selected.service();
        if (!metadata.getName().equals(setup.model())) throw new IllegalArgumentException("CHAT_MODEL_UNAVAILABLE");
        var context = contexts.getObject();
        var process = context.getProcessContext().getAgentProcess();
        int maxOutput = Math.min(limits.maxOutputTokens(), selected.maxOutputTokens());
        var guard = new ChatModelGuard(metadata.getChatModel(), process, metadata,
                new Budget(limits.costBudgetUsd(), Integer.MAX_VALUE, limits.tokenBudget()), limits.maxCycles(), checkActive,
                selected.finalRequest());
        guard.contextLimit(selected.tokens(), Math.min(limits.contextTokenLimit(), selected.contextWindow()
                - maxOutput));
        guard.executionScheduler(scheduler);
        guard.outputLimit(maxOutput);
        guard.synchronousLimit(searchLimits.helperCallLimit());
        SearchTool searchTool = null;
        try {
            var nativeService = selected.withModel(guard);
            var service = new StreamingLlmService(nativeService);
            var runner = context.ai().withLlmService(service);
            runner = runner.withLlm(Objects.requireNonNull(runner.getLlm()).withMaxTokens(maxOutput))
                    .withToolCallContext(Map.of("actor", setup.actor(), "tenant", setup.tenant(), "runId", setup.assistantMessageId()));
            var messages = new ArrayList<>(setup.messages());
            if (selected.toolCalling()) {
                var selectionRunner = context.ai().withLlmService(nativeService);
                selectionRunner = selectionRunner.withLlm(Objects.requireNonNull(selectionRunner.getLlm())
                        .withMaxTokens(Math.min(2048, maxOutput)).withoutThinking());
                searchTool = new SearchTool(search, setup.actor(), selectionRunner, selected.tokens(), searchLimits,
                        guard::checkActive, guard::availableContextTokens, events, cancellation, setup.messages(), setup.deadline(), timings);
                guard.evidenceAvailable(searchTool::hasEvidence);
                runner = runner.withTools(Tool.fromInstance(searchTool)).withToolCallInspectors(searchTool);
            }
            Duration remaining = Duration.between(Instant.now(), setup.deadline());
            if (remaining.isNegative() || remaining.isZero()) throw new IllegalStateException("CHAT_DEADLINE");
            new StreamingPromptRunnerBuilder(runner).streaming().withMessages(messages).generateStream()
                    .takeUntilOther(cancellation).doOnNext(text -> { guard.checkActive(); output.accept(text); }).blockLast(remaining);
        } finally {
            if (searchTool != null) searchTool.close();
            var drained = searchTool == null ? CompletableFuture.<Void>completedFuture(null) : searchTool.whenDrained();
            try {
                // A timed-out provider can still record usage. Never persist an incomplete total as known.
                if (!drained.isDone()) accounting.accept(new Accounting(null, null, null));
                else {
                    var usage = process.usage();
                    accounting.accept(new Accounting(guard.usageKnown() && usage.getPromptTokens() != null ? usage.getPromptTokens().longValue() : null,
                            guard.usageKnown() && usage.getCompletionTokens() != null ? usage.getCompletionTokens().longValue() : null,
                            guard.usageKnown() && metadata.getPricingModel() != null ? process.cost() : null));
                }
            } finally { onDrained.accept(drained.thenRun(() -> processes.delete(process))); }
        }
    }
}
