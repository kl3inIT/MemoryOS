package io.memoryos.chat;

import io.memoryos.chat.application.ChatTurnPersistence;
import io.memoryos.chat.execution.ChatExecutionProperties;
import io.memoryos.chat.execution.ChatModelExecutor;
import io.memoryos.chat.execution.ChatTurnSetup;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import reactor.core.publisher.Sinks;

/** Background turn lifetime is independent of HTTP request/reader lifetime. Created only by the API. */
public final class ChatTurnService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ChatTurnService.class);
    private static final Set<String> FAILURE_CODES = Set.of("CHAT_OUTPUT_LIMIT", "CHAT_CYCLE_LIMIT", "CHAT_BUDGET_EXCEEDED",
            "CHAT_MODEL_UNAVAILABLE", "CHAT_INCOMPLETE_RESPONSE", "CHAT_LAST_CYCLE_TOOL_CALL", "CHAT_UNSUPPORTED_OPTIONS", "CHAT_DEADLINE", "CHAT_EMPTY_RESPONSE");
    private final ChatTurnPersistence persistence;
    private final ChatModelExecutor model;
    private final ChatExecutionProperties limits;
    private final TaskExecutor executor;
    private final StreamBufferWriter streams;
    private final ReentrantLock[] commands = new ReentrantLock[128];
    private final Semaphore permits;
    private final ConcurrentHashMap<UUID, Active> active = new ConcurrentHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    public ChatTurnService(ChatTurnPersistence persistence, ChatModelExecutor model, ChatExecutionProperties limits,
            TaskExecutor executor, StreamBufferWriter streams) {
        this.persistence = persistence;
        this.model = model;
        this.limits = limits;
        this.executor = executor;
        this.streams = streams;
        Arrays.setAll(commands, ignored -> new ReentrantLock());
        this.permits = new Semaphore(limits.concurrency());
    }

    public record Accepted(UUID userMessageId, UUID assistantMessageId) {}
    public record Cancellation(UUID assistantMessageId, ChatMessage.Status status) {}

    public Accepted send(ActorId actor, UUID session, UUID parent, UUID request, String text) {
        var lock = commandLock(session);
        lock.lock();
        try { return sendLocked(actor, session, parent, request, text); }
        finally { lock.unlock(); }
    }

    private Accepted sendLocked(ActorId actor, UUID session, UUID parent, UUID request, String text) {
        var previous = persistence.existing(actor, session, parent, request, text);
        if (previous.isPresent()) return accepted(previous.orElseThrow());
        model.requireAvailable();
        if (!accepting.get() || !permits.tryAcquire()) throw ChatException.busy();
        ChatTurnPersistence.Reservation reserved = null;
        boolean transferred = false;
        try {
            reserved = persistence.reserve(actor, session, parent, request, text, limits.deadline(), limits.contextTokenLimit());
            if (!reserved.created()) return accepted(reserved);
            var context = persistence.loadContext(actor, session, reserved);
            var setup = ChatTurnSetup.resolve(session, reserved.assistantMessageId(), context, limits.contextTokenLimit(),
                    model.resolve(context.model()));
            var run = new Active(setup);
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
                run.finished.complete(null);
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
            if (!transferred) permits.release();
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

    public StreamBufferWriter.Reader subscribe(ActorId actor, UUID session, UUID assistant, long after) {
        var lock = commandLock(session);
        lock.lock();
        try {
            persistence.authorizeReply(actor, session, assistant);
            return streams.subscribe(assistant, after);
        } finally { lock.unlock(); }
    }

    private ReentrantLock commandLock(UUID session) { return commands[Math.floorMod(session.hashCode(), commands.length)]; }

    private static Accepted accepted(ChatTurnPersistence.Reservation reservation) {
        return new Accepted(reservation.userMessageId(), reservation.assistantMessageId());
    }

    private void execute(Active run) {
        try {
            run.check();
            model.execute(run.setup, run::check, run.cancellation.asMono(),
                    text -> {
                        run.append(text, limits.maxAnswerCharacters());
                        streams.append(run.setup.assistantMessageId(), text);
                    }, accounting -> run.accounting = accounting);
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
            run.finished.complete(null);
        }
    }

    /** Only pending outcomes and stale deadlines touch the database; Stop is local. */
    public void maintain() {
        for (var run : active.values()) {
            if (run.outcome != null) { finalizeRun(run); continue; }
            if (!Instant.now().isBefore(run.setup.deadline())) run.cancel(StopReason.INTERRUPTED);
        }
        try { persistence.expireRuns(); }
        catch (RuntimeException failure) { LOG.warn("Chat deadline reconciliation unavailable"); }
    }

    private void finalizeRun(Active run) {
        synchronized (run) {
            if (active.get(run.setup.assistantMessageId()) != run) return;
            try {
                var outcome = run.outcome;
                if (outcome == null) return;
                var saved = persistence.finishAndRead(run.setup.sessionId(), run.setup.assistantMessageId(), outcome.status(),
                        outcome.content(), outcome.failure(), run.setup.model(), run.accounting.input(),
                        run.accounting.output(), run.accounting.cost());
                streams.finish(run.setup.assistantMessageId(), saved.status(), saved.failureCode());
                active.remove(run.setup.assistantMessageId(), run);
                permits.release();
            } catch (RuntimeException failure) { LOG.warn("Chat terminal persistence pending for run {}", run.setup.assistantMessageId()); }
        }
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
    private record Outcome(ChatMessage.Status status, String content, String failure) {}

    private static final class Active {
        final ChatTurnSetup setup;
        final StringBuilder content = new StringBuilder();
        final AtomicReference<StopReason> stopReason = new AtomicReference<>();
        final Sinks.One<Boolean> cancellation = Sinks.one();
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        volatile Outcome outcome;
        volatile ChatModelExecutor.Accounting accounting = new ChatModelExecutor.Accounting(null, null, null);
        Active(ChatTurnSetup setup) { this.setup = setup; }
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
        synchronized void finish(ChatMessage.Status status, String failure) {
            if (outcome == null) {
                if (stopReason.get() == StopReason.USER) outcome = new Outcome(ChatMessage.Status.CANCELED, content.toString(), null);
                else if (stopReason.get() == StopReason.INTERRUPTED) outcome = new Outcome(ChatMessage.Status.FAILED,
                        content.toString(), Instant.now().isBefore(setup.deadline()) ? "CHAT_INTERRUPTED" : "CHAT_DEADLINE");
                else outcome = new Outcome(status, content.toString(), failure);
            }
        }
        void check() {
            if (stopReason.get() != null || outcome != null) throw new CancellationException("Chat stopped");
            if (!Instant.now().isBefore(setup.deadline())) throw new IllegalStateException("CHAT_DEADLINE");
        }
    }
}
