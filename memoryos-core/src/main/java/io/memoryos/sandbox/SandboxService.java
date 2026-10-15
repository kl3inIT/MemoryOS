package io.memoryos.sandbox;

import io.memoryos.sandbox.persistence.JdbcSandboxRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A conversation's sandbox (MEM-270): found or started under the conversation's row lock, renewed on every use, and
 * replaced when the runtime no longer has it or it reached its total life. agents-flex renewed only when attaching
 * and never replaced an expired sandbox; both are handled here. The caller has already established that {@code actor}
 * may work in the conversation.
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

    private static final Logger LOG = LoggerFactory.getLogger(SandboxService.class);

    private final ObjectProvider<SandboxRuntime> runtimes;
    private final JdbcSandboxRepository workspaces;
    private final SandboxProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public SandboxService(ObjectProvider<SandboxRuntime> runtimes, JdbcSandboxRepository workspaces,
                          SandboxProperties properties, PlatformTransactionManager transactionManager) {
        this(runtimes, workspaces, properties, transactionManager, Clock.systemUTC());
    }

    SandboxService(ObjectProvider<SandboxRuntime> runtimes, JdbcSandboxRepository workspaces, SandboxProperties properties,
                   PlatformTransactionManager transactionManager, Clock clock) {
        this.runtimes = runtimes;
        this.workspaces = workspaces;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Whether this deployment has a sandbox at all. */
    public boolean available() {
        return properties.configured() && runtimes.getIfAvailable() != null;
    }

    /** The conversation's live sandbox, started when it has none. */
    public Sandbox acquire(TenantId tenant, ActorId actor, UUID conversation) {
        SandboxRuntime runtime = runtime();
        Sandbox sandbox = transactions.execute(_ -> {
            workspaces.ensure(tenant, conversation, actor, runtime.name());
            var workspace = workspaces.lock(tenant, conversation).orElseThrow();
            Instant now = clock.instant();
            String recorded = workspace.runtimeId();
            if (recorded != null && workspace.runtime().equals(runtime.name())) {
                if (workspace.startedAt() != null && workspace.startedAt().plus(properties.maxLifetime()).isAfter(now)) {
                    try {
                        call(() -> { runtime.renew(recorded, properties.idleTimeout()); return recorded; });
                        workspaces.used(tenant, conversation, now);
                        return new Sandbox(tenant, conversation, recorded);
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
            int running = workspaces.running(runtime.name(), now.minus(properties.idleTimeout()));
            if (running >= properties.maxRunning()) {
                LOG.atWarn().addKeyValue("event", "sandbox.busy").addKeyValue("running", running)
                        .addKeyValue("error_code", "SANDBOX_BUSY").log("Sandbox bound reached");
                throw SandboxException.busy(properties.maxRunning());
            }
            String id = call(() -> runtime.create(spec(tenant, conversation)));
            workspaces.started(tenant, conversation, runtime.name(), id, now);
            LOG.atInfo().addKeyValue("event", "sandbox.started").log("Sandbox started");
            return new Sandbox(tenant, conversation, id);
        });
        if (sandbox == null) throw new IllegalStateException("No sandbox was acquired");
        return sandbox;
    }

    /** Runs {@code command} in the conversation's sandbox, starting another once if the recorded one is gone. */
    public SandboxCommandResult run(TenantId tenant, ActorId actor, UUID conversation, SandboxCommand command) {
        return withSandbox(tenant, actor, conversation, (runtime, id) -> runtime.run(id, command));
    }

    public void write(TenantId tenant, ActorId actor, UUID conversation, String path, byte[] content) {
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

    /** A runtime failure other than a gone sandbox becomes {@code SANDBOX_UNAVAILABLE}. */
    private static <T> T call(Callable<T> call) {
        try {
            return call.call();
        } catch (SandboxGoneException | SandboxException | IllegalArgumentException expected) {
            throw expected;
        } catch (Exception failed) {
            throw SandboxException.unavailable(failed);
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T apply(SandboxRuntime runtime, String id);
    }
}
