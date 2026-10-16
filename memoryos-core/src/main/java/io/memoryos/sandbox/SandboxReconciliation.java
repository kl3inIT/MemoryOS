package io.memoryos.sandbox;

import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.sandbox.persistence.JdbcSandboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Worker's sweep of sandboxes (MEM-270), as Dust's reaper sweeps every five minutes. A sandbox the runtime holds
 * that no row records is killed: a transaction that rolled back after starting it, or a row forgotten. A row whose
 * sandbox the runtime no longer holds is cleared, so the running bound counts what is alive. Workspace archives no
 * conversation starts from any more are deleted from Object Storage.
 */
@Component
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxReconciliation {
    /**
     * A sandbox younger than this may belong to a start that has not recorded it yet: a start, capped by OpenSandbox
     * at 90 seconds ({@code OpenSandboxProperties.MAX_READY_TIMEOUT}), then its workspace restore.
     */
    static final Duration GRACE = SandboxService.STARTING_WINDOW;
    private static final Logger LOG = LoggerFactory.getLogger(SandboxReconciliation.class);

    /** Archives deleted in one sweep; the rest wait for the next. */
    static final int ARCHIVES_PER_SWEEP = 100;

    private final ObjectProvider<SandboxRuntime> runtimes;
    private final ObjectProvider<ObjectStorage> storages;
    private final JdbcSandboxRepository workspaces;
    private final SandboxProperties properties;
    private final WorkspaceArchives archives;
    private final Clock clock;

    @Autowired
    public SandboxReconciliation(ObjectProvider<SandboxRuntime> runtimes, ObjectProvider<ObjectStorage> storages,
                                 JdbcSandboxRepository workspaces, SandboxProperties properties,
                                 PlatformTransactionManager transactionManager) {
        this(runtimes, storages, workspaces, properties, transactionManager, Clock.systemUTC());
    }

    SandboxReconciliation(ObjectProvider<SandboxRuntime> runtimes, ObjectProvider<ObjectStorage> storages,
                          JdbcSandboxRepository workspaces, SandboxProperties properties,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.runtimes = runtimes;
        this.storages = storages;
        this.workspaces = workspaces;
        this.properties = properties;
        this.archives = new WorkspaceArchives(workspaces, new TransactionTemplate(transactionManager), clock);
        this.clock = clock;
    }

    public void sweep() {
        ObjectStorage storage = storages.getIfAvailable();
        if (storage != null) {
            int deleted = archives.sweep(storage, ARCHIVES_PER_SWEEP);
            if (deleted > 0) LOG.atInfo().addKeyValue("event", "sandbox.workspace.archives_deleted")
                    .addKeyValue("deleted", deleted).log("Unused workspace archives deleted");
        }
        SandboxRuntime runtime = properties.configured() ? runtimes.getIfAvailable() : null;
        if (runtime == null) return;
        // Rows first: a sandbox started after this read is young and spared below, and every row read here names a
        // sandbox that existed before the runtime is asked.
        var rows = workspaces.recorded(runtime.name());
        Map<String, Instant> alive = runtime.running(Map.of(SandboxService.MANAGED, "true"));
        Instant now = clock.instant();
        Set<String> recorded = new HashSet<>();
        int cleared = 0;
        for (var row : rows) {
            recorded.add(row.runtimeId());
            if (!alive.containsKey(row.runtimeId())) {
                workspaces.cleared(row.tenant(), row.conversation(), row.runtimeId());
                cleared++;
            }
        }
        int killed = 0;
        for (var sandbox : alive.entrySet()) {
            if (recorded.contains(sandbox.getKey()) || sandbox.getValue().plus(GRACE).isAfter(now)) continue;
            try {
                runtime.kill(sandbox.getKey());
                killed++;
            } catch (RuntimeException failed) {
                LOG.atWarn().addKeyValue("event", "sandbox.kill_failed")
                        .addKeyValue("error_type", failed.getClass().getSimpleName()).log("Orphan sandbox could not be killed");
            }
        }
        if (cleared > 0 || killed > 0)
            LOG.atInfo().addKeyValue("event", "sandbox.reconciled").addKeyValue("cleared", cleared)
                    .addKeyValue("killed", killed).log("Sandboxes reconciled");
    }
}
