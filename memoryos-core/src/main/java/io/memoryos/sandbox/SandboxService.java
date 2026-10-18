package io.memoryos.sandbox;

import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.sandbox.persistence.JdbcSandboxRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A conversation's sandbox (MEM-270): found or started under the conversation's row lock, renewed on every use, and
 * replaced when the runtime no longer has it or it reached its total life. agents-flex renewed only when attaching
 * and never replaced an expired sandbox; both are handled here. The caller has already established that {@code actor}
 * may work in the conversation. A new sandbox starts from the conversation's last workspace archive
 * ({@link WorkspaceArchives}).
 */
@Service
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxService {
    /** Labels every sandbox carries, so the Worker finds the ones MemoryOS started. */
    static final String MANAGED = "memoryos.io/managed";
    static final String TENANT = "memoryos.io/tenant";
    static final String CONVERSATION = "memoryos.io/conversation";
    /** The computer image's unprivileged user (MEM-270 probe: OpenSandbox runs commands as root otherwise). */
    public static final int USER = 1000;
    public static final String WORKSPACE = "/workspace";

    /**
     * How long a start may take before its place in the bound is freed: above the runtime's request deadline (90 s)
     * and a workspace restore ({@link WorkspaceArchives#RESTORE_TIMEOUT}) together.
     */
    static final Duration STARTING_WINDOW = Duration.ofMinutes(10);
    private static final Duration STARTING_POLL = Duration.ofMillis(500);
    /** How many {@link #scratch} sandboxes run at once in one process, and how long one lives at most. */
    static final int SCRATCH_PARALLEL = 2;
    private static final Duration SCRATCH_LIFE = Duration.ofMinutes(5);
    private final Semaphore scratches = new Semaphore(SCRATCH_PARALLEL);
    private static final Logger LOG = LoggerFactory.getLogger(SandboxService.class);

    private final ObjectProvider<SandboxRuntime> runtimes;
    private final ObjectProvider<ObjectStorage> storages;
    private final JdbcSandboxRepository workspaces;
    private final SandboxProperties properties;
    private final TransactionTemplate transactions;
    private final WorkspaceArchives archives;
    private final Clock clock;

    @Autowired
    public SandboxService(ObjectProvider<SandboxRuntime> runtimes, ObjectProvider<ObjectStorage> storages,
                          JdbcSandboxRepository workspaces, SandboxProperties properties,
                          PlatformTransactionManager transactionManager) {
        this(runtimes, storages, workspaces, properties, transactionManager, Clock.systemUTC());
    }

    SandboxService(ObjectProvider<SandboxRuntime> runtimes, ObjectProvider<ObjectStorage> storages,
                   JdbcSandboxRepository workspaces, SandboxProperties properties,
                   PlatformTransactionManager transactionManager, Clock clock) {
        this.runtimes = runtimes;
        this.storages = storages;
        this.workspaces = workspaces;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.archives = new WorkspaceArchives(workspaces, transactions, clock);
        this.clock = clock;
    }

    /** Whether this deployment has a sandbox at all. */
    public boolean available() {
        return properties.configured() && runtimes.getIfAvailable() != null;
    }

    /**
     * The conversation's live sandbox, started when it has none. A start takes its place in the running bound in a
     * short transaction, creates the sandbox outside any transaction and records it in another, so a slow or hung
     * runtime never holds a database connection or the start lock; another call of the same conversation waits for
     * that start rather than making a second sandbox.
     */
    public Sandbox acquire(TenantId tenant, ActorId actor, UUID conversation) {
        SandboxRuntime runtime = runtime();
        long deadline = System.nanoTime() + STARTING_WINDOW.toNanos();
        while (true) {
            Step step = transactions.execute(_ -> decide(runtime, tenant, actor, conversation));
            if (step instanceof Step.Ready ready) return ready.sandbox();
            if (step instanceof Step.Start begun) return start(runtime, tenant, conversation, begun);
            if (System.nanoTime() > deadline) throw SandboxException.unavailable(new IllegalStateException("A start never ended"));
            try {
                Thread.sleep(STARTING_POLL);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw SandboxException.unavailable(interrupted);
            }
        }
    }

    /** Under the conversation's row lock: the recorded sandbox renewed, a start reserved, or a start to wait for. */
    private Step decide(SandboxRuntime runtime, TenantId tenant, ActorId actor, UUID conversation) {
        workspaces.ensure(tenant, conversation, actor, runtime.name());
        var workspace = workspaces.lock(tenant, conversation).orElseThrow();
        Instant now = clock.instant();
        if (workspace.startingSince() != null && workspace.startingSince().isAfter(now.minus(STARTING_WINDOW)))
            return new Step.Wait();
        String recorded = workspace.runtimeId();
        if (recorded != null && workspace.runtime().equals(runtime.name())) {
            if (workspace.startedAt() != null && workspace.startedAt().plus(properties.maxLifetime()).isAfter(now)) {
                try {
                    call(() -> { runtime.renew(recorded, properties.idleTimeout()); return recorded; });
                    workspaces.used(tenant, conversation, now);
                    return new Step.Ready(new Sandbox(tenant, conversation, recorded));
                } catch (SandboxGoneException gone) {
                    LOG.atInfo().addKeyValue("event", "sandbox.replaced").addKeyValue("reason", "gone")
                            .log("Sandbox gone; starting another");
                }
            } else {
                end(runtime, recorded);
                LOG.atInfo().addKeyValue("event", "sandbox.replaced").addKeyValue("reason", "lifetime")
                        .log("Sandbox reached its lifetime; starting another");
            }
            workspaces.cleared(tenant, conversation, recorded);
        }
        workspaces.lockStarts();
        int running = workspaces.running(runtime.name(), now.minus(properties.idleTimeout()), now.minus(STARTING_WINDOW));
        if (running >= properties.maxRunning()) {
            LOG.atWarn().addKeyValue("event", "sandbox.busy").addKeyValue("running", running)
                    .addKeyValue("error_code", "SANDBOX_BUSY").log("Sandbox bound reached");
            throw SandboxException.busy(properties.maxRunning());
        }
        UUID token = UUID.randomUUID();
        workspaces.reserve(tenant, conversation, token, now);
        return new Step.Start(token, workspace.archiveKey());
    }

    /**
     * Creates the sandbox outside any transaction, restores the conversation's workspace into it and records it; a
     * failed start gives its place back.
     */
    private Sandbox start(SandboxRuntime runtime, TenantId tenant, UUID conversation, Step.Start begun) {
        UUID token = begun.token();
        String id;
        try {
            id = call(() -> runtime.create(spec(tenant, conversation)));
        } catch (RuntimeException failed) {
            transactions.executeWithoutResult(_ -> workspaces.released(tenant, conversation, token));
            throw failed;
        }
        // Before the sandbox is recorded, so no other call of the conversation sees it half restored.
        if (begun.archiveKey() != null) archives.restore(runtime, storages.getIfAvailable(), id, begun.archiveKey());
        boolean recorded;
        try {
            recorded = Boolean.TRUE.equals(transactions.execute(_ ->
                    workspaces.started(tenant, conversation, token, runtime.name(), id, clock.instant())));
        } catch (RuntimeException failed) {
            end(runtime, id);
            throw failed;
        }
        if (!recorded) {
            // A start this old was taken over; its sandbox belongs to nobody.
            end(runtime, id);
            throw SandboxException.unavailable(new IllegalStateException("The start was superseded"));
        }
        LOG.atInfo().addKeyValue("event", "sandbox.started").log("Sandbox started");
        return new Sandbox(tenant, conversation, id);
    }

    /**
     * Archives the workspace of the conversation's live sandbox, as the last step of a turn that used it; nothing
     * happens without one. Never throws: a workspace not saved only means the next sandbox starts from an older one.
     */
    public void save(TenantId tenant, UUID conversation) {
        SandboxRuntime runtime = properties.configured() ? runtimes.getIfAvailable() : null;
        ObjectStorage storage = storages.getIfAvailable();
        if (runtime == null || storage == null) return;
        try {
            var id = workspaces.runtimeId(tenant, conversation, runtime.name());
            if (id.isPresent()) archives.save(runtime, storage, tenant, conversation, id.get());
        } catch (SandboxGoneException gone) {
            LOG.atInfo().addKeyValue("event", "sandbox.workspace.not_saved").addKeyValue("reason", "gone")
                    .log("Workspace not saved; the sandbox is gone");
        } catch (RuntimeException failed) {
            LOG.atWarn().addKeyValue("event", "sandbox.workspace.not_saved").addKeyValue("reason", "failed")
                    .addKeyValue("error_type", failed.getClass().getSimpleName()).log("Workspace not saved");
        }
    }

    /**
     * Keeps the conversation's sandbox alive for at least {@code atLeast} from now, as a background command needs: a
     * later use renews it by the idle timeout from a later moment, so it never shortens this.
     */
    public void keepAlive(TenantId tenant, ActorId actor, UUID conversation, Duration atLeast) {
        Duration life = atLeast.compareTo(properties.idleTimeout()) > 0 ? atLeast : properties.idleTimeout();
        withSandbox(tenant, actor, conversation, (runtime, id) -> { runtime.renew(id, life); return id; });
    }

    /** Runs {@code command} in the conversation's sandbox, starting another once if the recorded one is gone. */
    public SandboxCommandResult run(TenantId tenant, ActorId actor, UUID conversation, SandboxCommand command) {
        return withSandbox(tenant, actor, conversation, (runtime, id) -> runtime.run(id, command));
    }

    public void write(TenantId tenant, ActorId actor, UUID conversation, String path, byte[] content) {
        withSandbox(tenant, actor, conversation, (runtime, id) -> { runtime.write(id, path, content); return path; });
    }

    /** Writes the file from {@code content}, which the caller closes, without holding it in memory where the runtime streams. */
    public void writeStream(TenantId tenant, ActorId actor, UUID conversation, String path, InputStream content) {
        withSandbox(tenant, actor, conversation, (runtime, id) -> { runtime.write(id, path, content); return path; });
    }

    public byte[] read(TenantId tenant, ActorId actor, UUID conversation, String path, long maxBytes) {
        return withSandbox(tenant, actor, conversation, (runtime, id) -> runtime.read(id, path, maxBytes));
    }

    public InputStream open(TenantId tenant, ActorId actor, UUID conversation, String path) {
        return withSandbox(tenant, actor, conversation, (runtime, id) -> runtime.open(id, path));
    }

    public List<SandboxEntry> list(TenantId tenant, ActorId actor, UUID conversation, String path, int depth) {
        return withSandbox(tenant, actor, conversation, (runtime, id) -> runtime.list(id, path, depth));
    }

    /** Whether the deployment has a sandbox and its runtime answers: a listing of MemoryOS's sandboxes succeeds. */
    public boolean healthy() {
        SandboxRuntime runtime = properties.configured() ? runtimes.getIfAvailable() : null;
        if (runtime == null) return false;
        try {
            runtime.running(Map.of(MANAGED, "true"));
            return true;
        } catch (RuntimeException failed) {
            LOG.atWarn().addKeyValue("event", "sandbox.health.failed").addKeyValue("error_type", failed.getClass().getSimpleName())
                    .log("Sandbox runtime did not answer");
            return false;
        }
    }

    /**
     * Runs {@code work} in a sandbox of no conversation, killed when it returns, as Onyx Craft converts a deck in its
     * sandbox (MEM-271). Such work is short and rare, so it is not counted in the running bound: at most
     * {@link #SCRATCH_PARALLEL} run at once in this process, and one more is {@code SANDBOX_BUSY}. One a crash leaves
     * behind has no row, so the Worker's reconciliation kills it.
     */
    public <T> T scratch(TenantId tenant, ScratchWork<T> work) {
        SandboxRuntime runtime = runtime();
        if (!scratches.tryAcquire()) throw SandboxException.busy(SCRATCH_PARALLEL);
        try {
            var spec = new SandboxSpec(properties.computerImage(), SCRATCH_LIFE, properties.cpu(), properties.memory(),
                    List.of(), Map.of(MANAGED, "true", TENANT, tenant.value().toString()));
            String id = call(() -> runtime.create(spec));
            try {
                return work.apply(new Scratch() {
                    @Override public SandboxCommandResult run(SandboxCommand command) { return call(() -> runtime.run(id, command)); }
                    @Override public void write(String path, InputStream content) {
                        call(() -> { runtime.write(id, path, content); return path; });
                    }
                    @Override public byte[] read(String path, long maxBytes) { return call(() -> runtime.read(id, path, maxBytes)); }
                });
            } finally {
                end(runtime, id);
            }
        } finally {
            scratches.release();
        }
    }

    /** A sandbox of no conversation, for {@link #scratch}. */
    public interface Scratch {
        SandboxCommandResult run(SandboxCommand command);

        void write(String path, InputStream content);

        byte[] read(String path, long maxBytes);
    }

    @FunctionalInterface
    public interface ScratchWork<T> {
        T apply(Scratch sandbox);
    }

    private <T> T withSandbox(TenantId tenant, ActorId actor, UUID conversation, Operation<T> operation) {
        SandboxRuntime runtime = runtime();
        Sandbox sandbox = acquire(tenant, actor, conversation);
        try {
            return call(() -> operation.apply(runtime, sandbox.runtimeId()));
        } catch (SandboxGoneException gone) {
            // Expired between the renewal and the call; the next acquire replaces it.
            Sandbox replaced = acquire(tenant, actor, conversation);
            return call(() -> operation.apply(runtime, replaced.runtimeId()));
        }
    }

    private SandboxSpec spec(TenantId tenant, UUID conversation) {
        return new SandboxSpec(properties.computerImage(), properties.idleTimeout(), properties.cpu(), properties.memory(),
                properties.allowedDomains(), Map.of(MANAGED, "true", TENANT, tenant.value().toString(),
                CONVERSATION, conversation.toString()));
    }

    private void end(SandboxRuntime runtime, String id) {
        try {
            runtime.kill(id);
        } catch (RuntimeException failed) {
            // The Worker's reconciliation kills it later; the conversation gets a fresh sandbox now.
            LOG.atWarn().addKeyValue("event", "sandbox.kill_failed").addKeyValue("error_type", failed.getClass().getSimpleName())
                    .log("Sandbox could not be killed");
        }
    }

    private SandboxRuntime runtime() {
        SandboxRuntime runtime = properties.configured() ? runtimes.getIfAvailable() : null;
        if (runtime == null) throw SandboxException.notConfigured();
        return runtime;
    }

    /** A runtime failure other than a gone sandbox or a refused file becomes {@code SANDBOX_UNAVAILABLE}. */
    private static <T> T call(Callable<T> call) {
        try {
            return call.call();
        } catch (SandboxGoneException | SandboxFileException | SandboxException | IllegalArgumentException expected) {
            throw expected;
        } catch (Exception failed) {
            throw SandboxException.unavailable(failed);
        }
    }

    /** What {@link #decide} found under the row lock. */
    private sealed interface Step {
        record Ready(Sandbox sandbox) implements Step {}

        record Start(UUID token, @Nullable String archiveKey) implements Step {}

        record Wait() implements Step {}
    }

    @FunctionalInterface
    private interface Operation<T> {
        T apply(SandboxRuntime runtime, String id);
    }
}
