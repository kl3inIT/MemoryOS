package io.memoryos.chat;

import io.memoryos.chat.application.ChatTurnPersistence;
import io.memoryos.chat.execution.ChatExecutionProperties;
import io.memoryos.chat.execution.ChatModelExecutor;
import io.memoryos.chat.execution.ChatTurnSetup;
import io.memoryos.chat.catalog.ChatModelResolver;
import org.jspecify.annotations.Nullable;
import io.memoryos.iam.ActorId;
import io.memoryos.chat.streaming.StreamBufferWriter;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.UUID;
import java.util.Set;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import reactor.core.publisher.Sinks;

/** Background turn lifetime is independent of HTTP request/reader lifetime. Created only by the API. */
public final class ChatTurnService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ChatTurnService.class);
    private static final Set<String> FAILURE_CODES = Set.of("CHAT_OUTPUT_LIMIT", "CHAT_CYCLE_LIMIT", "CHAT_BUDGET_EXCEEDED",
            "CHAT_MODEL_UNAVAILABLE", "CHAT_INCOMPLETE_RESPONSE", "CHAT_LAST_CYCLE_TOOL_CALL", "CHAT_UNSUPPORTED_OPTIONS", "CHAT_DEADLINE", "CHAT_EMPTY_RESPONSE", "CHAT_CONTEXT_LIMIT");
    private final ChatTurnPersistence persistence;
    private final ChatModelExecutor model;
    private final ChatModelResolver models;
    private final ChatExecutionProperties limits;
    private final TaskExecutor executor;
    private final StreamBufferWriter streams;
    private final ReentrantLock[] commands = new ReentrantLock[128];
    private final Semaphore permits;
    private final ConcurrentHashMap<UUID, Active> active = new ConcurrentHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public ChatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
            TaskExecutor executor, StreamBufferWriter streams, ChatModelResolver models) {
        this.persistence = persistence;
        this.model = model;
        this.models = models;
        this.limits = limits;
        this.executor = executor;
        this.streams = streams;
        Arrays.setAll(commands, ignored -> new ReentrantLock());
        this.permits = new Semaphore(limits.concurrency());
    }

    public record Accepted(UUID userMessageId, UUID assistantMessageId, @Nullable UUID modelConfigurationId, @Nullable String fallbackReason) {}
    public record Cancellation(UUID assistantMessageId, ChatMessage.Status status) {}

    /** The browser invokes this after the first completed exchange, never in the answer stream. */
    public void generateTitle(ActorId actor, UUID session) {
        if (!accepting.get() || !permits.tryAcquire()) return;
        try {
            var input = persistence.claimTitle(actor, session);
            if (input.isEmpty()) return;
            try (var selected = models.resolve(actor, session, null)) {
                var title = model.generateTitle(selected.binding(), input.orElseThrow().messages());
                persistence.completeTitle(actor, input.orElseThrow(), title);
            } catch (RuntimeException failure) {
                // Preserve the initial short title. Never log conversation/provider payloads.
                LOG.warn("Chat naming unavailable for session {} ({})", session, failure.getClass().getSimpleName());
            }
        } finally { permits.release(); }
    }

    public Accepted send(ActorId actor, UUID session, UUID parent, UUID request, String text, @Nullable UUID modelConfigurationId) {
        return command(actor, session, new ChatCommand(ChatCommand.Operation.SEND, parent, request, text, modelConfigurationId));
    }

    public Accepted command(ActorId actor, UUID session, ChatCommand command) {
        var lock = commandLock(session);
        lock.lock();
        try { return sendLocked(actor, session, command); }
        finally { lock.unlock(); }
    }

    private Accepted sendLocked(ActorId actor, UUID session, ChatCommand command) {
        var previous = persistence.existing(actor, session, command);
        if (previous.isPresent()) return accepted(previous.orElseThrow());
        if (!accepting.get() || !permits.tryAcquire()) throw ChatException.busy();
        ChatTurnPersistence.Reservation reserved = null;
        ChatModelResolver.Resolved resolved = null;
        boolean transferred = false;
        try {
            resolved = models.resolve(actor, session, command.modelConfigurationId());
            var binding = resolved.binding();
            String contribution = java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(new com.embabel.common.ai.prompt.CurrentDate().contribution()),
                    binding.service().getPromptContributors().stream().map(com.embabel.common.ai.prompt.PromptContributor::contribution))
                    .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.joining("\n----\n"));
            int contextLimit = Math.min(limits.contextTokenLimit(), binding.contextWindow() - Math.min(limits.maxOutputTokens(), binding.maxOutputTokens()));
            reserved = persistence.reserve(actor, session, command, limits.deadline(), contextLimit,
                    new ChatTurnPersistence.ModelSelection(command.modelConfigurationId(), resolved.modelConfigurationId(),
                            resolved.fallbackReason(), binding, resolved.contextRevision(), contribution));
            if (!reserved.created()) return accepted(reserved);
            var context = persistence.loadContext(actor, session, reserved);
            var setup = ChatTurnSetup.resolve(session, reserved.assistantMessageId(), context, contextLimit, binding, contribution);
            var run = new Active(setup, resolved);
            streams.open(setup.assistantMessageId());
            active.put(setup.assistantMessageId(), run);
            transferred = true;
            try {
                if (!accepting.get()) throw ChatException.busy();
                executor.execute(() -> execute(run));
            }
            catch (RuntimeException failure) {
                run.finish(ChatMessage.Status.FAILED, "CHAT_SUBMIT_FAILED");
                finalizeRun(run);
                retireWhenDrained(run);
                throw failure;
            }
            return accepted(reserved);
        } catch (RuntimeException failure) {
            if (!transferred) {
                // Setup errors still own a reserved row; finish it without starting the model.
                if (reserved != null && reserved.created()) persistence.finish(session, reserved.assistantMessageId(),
                        ChatMessage.Status.FAILED, "", "CHAT_SETUP_FAILED", null, null, null, null);
            }
            throw failure;
        } finally {
            if (!transferred) {
                if (resolved != null) resolved.close();
                permits.release();
            }
        }
    }

    public Cancellation cancel(ActorId actor, UUID session, UUID assistant) {
        var lock = commandLock(session);
        lock.lock();
        try {
            var status = persistence.authorizeReply(actor, session, assistant);
            var run = active.get(assistant);
            if (run != null && status == ChatMessage.Status.RUNNING) run.cancel(StopReason.USER);
            return new Cancellation(assistant, status);
        } finally { lock.unlock(); }
    }

    public Supplier<StreamBufferWriter.Reader> subscribe(ActorId actor, UUID session, UUID assistant, long after) {
        var lock = commandLock(session);
        lock.lock();
        try {
            persistence.authorizeReply(actor, session, assistant);
            streams.validateSubscription(assistant, after);
            // Authorization/cursor errors remain synchronous; no reader slot is held until subscription.
            return () -> {
                lock.lock();
                try { persistence.authorizeReply(actor, session, assistant); return streams.subscribe(assistant, after); }
                finally { lock.unlock(); }
            };
        } finally { lock.unlock(); }
    }

    private ReentrantLock commandLock(UUID session) { return commands[Math.floorMod(session.hashCode(), commands.length)]; }

    public void delete(ActorId actor, UUID session) {
        var lock = commandLock(session);
        lock.lock();
        try {
            var messages = persistence.delete(actor, session);
            for (UUID message : messages) {
                var run = active.get(message);
                if (run != null) { run.deleted = true; run.cancel(StopReason.USER); }
                streams.discard(message);
            }
        } finally { lock.unlock(); }
    }

    private static Accepted accepted(ChatTurnPersistence.Reservation reservation) {
        return new Accepted(reservation.userMessageId(), reservation.assistantMessageId(), reservation.modelConfigurationId(), reservation.fallbackReason());
    }

    private void execute(Active run) {
        try {
            run.check();
            model.execute(run.setup, run::check, run.cancellation.asMono(),
                    text -> {
                        run.append(text, limits.maxAnswerCharacters());
                        streams.append(run.setup.assistantMessageId(), text);
                    }, accounting -> run.accounting = accounting, event -> {
                        run.searchEvent(event);
                        streams.search(run.setup.assistantMessageId(), event);
                    }, draining -> run.draining = draining);
            run.check();
            if (run.content.isEmpty()) throw new IllegalStateException("CHAT_EMPTY_RESPONSE");
            run.finish(ChatMessage.Status.COMPLETED, null);
        } catch (RuntimeException failure) {
            boolean userStop = run.stopReason.get() == StopReason.USER;
            String code = !Instant.now().isBefore(run.setup.deadline()) ? "CHAT_DEADLINE"
                    : run.stopReason.get() == StopReason.INTERRUPTED ? "CHAT_INTERRUPTED" : failureCode(failure);
            run.finish(userStop ? ChatMessage.Status.CANCELED : ChatMessage.Status.FAILED,
                    userStop ? null : code);
            // Provider exceptions may contain prompts/credentials. Never log their payload or stack here.
            if (!userStop) LOG.warn("Chat run {} failed: {} ({})", run.setup.assistantMessageId(), code, failure.getClass().getSimpleName());
        } finally {
            // Also close the product lifecycle if framework linkage or another Error escapes the task.
            run.finish(ChatMessage.Status.FAILED, "CHAT_EXECUTION_FAILED");
            finalizeRun(run);
            retireWhenDrained(run);
        }
    }

    private void retireWhenDrained(Active run) {
        run.draining.whenComplete((_, failure) -> {
            if (failure != null) LOG.warn("Chat native cleanup failed for run {} ({})",
                    run.setup.assistantMessageId(), failure.getClass().getSimpleName());
            try { run.resolved.close(); }
            finally {
                run.drained = true;
                // Terminal persistence and resource retirement may finish in either order.
                releaseIfFinished(run);
                run.finished.complete(null);
            }
        });
    }

    private void releaseIfFinished(Active run) {
        if (run.persisted && run.drained && active.remove(run.setup.assistantMessageId(), run)) permits.release();
    }

    /** Only pending outcomes and stale deadlines touch the database; Stop is local. */
    public void maintain() {
        for (var run : active.values()) {
            if (run.outcome != null) { finalizeRun(run); continue; }
            if (!Instant.now().isBefore(run.setup.deadline())) run.cancel(StopReason.INTERRUPTED);
        }
        try { persistence.expireRuns(); }
        catch (RuntimeException failure) { LOG.warn("Chat deadline reconciliation unavailable ({})", failure.getClass().getSimpleName()); }
    }

    private void finalizeRun(Active run) {
        if (!run.finalizing.tryLock()) return;
        try {
            if (active.get(run.setup.assistantMessageId()) != run) return;
            try {
                var outcome = run.outcome;
                if (outcome == null) return;
                if (!run.persisted) {
                    var saved = persistence.finishAndRead(run.setup.sessionId(), run.setup.assistantMessageId(), outcome.status(),
                            outcome.content(), outcome.failure(), run.setup.model(), run.accounting.input(),
                            run.accounting.output(), run.accounting.cost(), outcome.sources());
                    if (!run.deleted) streams.finish(run.setup.assistantMessageId(), saved.status(), saved.failureCode());
                    run.persisted = true;
                }
                releaseIfFinished(run);
            } catch (RuntimeException failure) { LOG.warn("Chat terminal persistence pending for run {}", run.setup.assistantMessageId()); }
        } finally { run.finalizing.unlock(); }
    }

    @Override
    public void close() {
        accepting.set(false);
        var draining = active.values().toArray(Active[]::new);
        for (var run : draining) run.cancel(StopReason.INTERRUPTED);
        try {
            CompletableFuture.allOf(Arrays.stream(draining).map(run -> run.finished).toArray(CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException incomplete) {
            LOG.warn("Chat shutdown drain incomplete; durable deadline reconciliation remains responsible");
        }
    }

    private static String failureCode(Throwable failure) {
        // Exact allowlist only: arbitrary provider messages can contain private content.
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (failure.getMessage() != null && FAILURE_CODES.contains(failure.getMessage())) return failure.getMessage();
        }
        return "CHAT_EXECUTION_FAILED";
    }

    private enum StopReason { USER, INTERRUPTED }
    private record Outcome(ChatMessage.Status status, String content, String failure, List<ChatSource> sources) {}

    private static final class Active {
        final ChatTurnSetup setup;
        final ChatModelResolver.Resolved resolved;
        final StringBuilder content = new StringBuilder();
        final List<ChatSource> sources = new ArrayList<>();
        final AtomicReference<StopReason> stopReason = new AtomicReference<>();
        final Sinks.One<Boolean> cancellation = Sinks.one();
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        CompletableFuture<Void> draining = CompletableFuture.completedFuture(null);
        volatile boolean drained;
        volatile boolean persisted;
        volatile boolean deleted;
        // Serializes persistence retries without holding the state monitor used by Stop/text callbacks.
        final ReentrantLock finalizing = new ReentrantLock();
        volatile Outcome outcome;
        volatile ChatModelExecutor.Accounting accounting = new ChatModelExecutor.Accounting(null, null, null);
        Active(ChatTurnSetup setup, ChatModelResolver.Resolved resolved) { this.setup = setup; this.resolved = resolved; }
        synchronized void cancel(StopReason reason) {
            if (outcome != null) return;
            stopReason.compareAndSet(null, reason);
            cancellation.tryEmitValue(true);
        }
        synchronized void append(String text, int limit) {
            check();
            if (content.length() + text.length() > limit) throw new IllegalStateException("CHAT_OUTPUT_LIMIT");
            content.append(text);
        }
        synchronized void searchEvent(ChatSearchEvent event) {
            check();
            if (event.source() != null) {
                if (sources.size() >= 24 || event.source().citationId() != sources.size() + 1)
                    throw new IllegalStateException("Invalid Chat evidence sequence");
                sources.add(event.source());
            }
        }
        synchronized void finish(ChatMessage.Status status, String failure) {
            if (outcome == null) {
                if (stopReason.get() == StopReason.USER) outcome = new Outcome(ChatMessage.Status.CANCELED, content.toString(), null, List.copyOf(sources));
                else if (stopReason.get() == StopReason.INTERRUPTED) outcome = new Outcome(ChatMessage.Status.FAILED,
                        content.toString(), Instant.now().isBefore(setup.deadline()) ? "CHAT_INTERRUPTED" : "CHAT_DEADLINE", List.copyOf(sources));
                else outcome = new Outcome(status, content.toString(), failure, List.copyOf(sources));
            }
        }
        void check() {
            if (stopReason.get() != null || outcome != null) throw new CancellationException("Chat stopped");
            if (!Instant.now().isBefore(setup.deadline())) throw new IllegalStateException("CHAT_DEADLINE");
        }
    }
}
