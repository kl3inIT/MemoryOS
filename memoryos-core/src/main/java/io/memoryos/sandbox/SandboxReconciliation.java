package io.memoryos.sandbox;

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

/**
 * The Worker's sweep of sandboxes (MEM-270), as Dust's reaper sweeps every five minutes. A sandbox the runtime holds
 * that no row records is killed: a transaction that rolled back after starting it, or a row forgotten. A row whose
 * sandbox the runtime no longer holds is cleared, so the running bound counts what is alive.
 */
@Component
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxReconciliation {
    /**
     * A sandbox younger than this may belong to a start that has not recorded it yet; OpenSandbox caps a start at
     * 90 seconds ({@code OpenSandboxProperties.MAX_READY_TIMEOUT}).
     */
    static final Duration GRACE = Duration.ofMinutes(2);
    private static final Logger LOG = LoggerFactory.getLogger(SandboxReconciliation.class);

    private final ObjectProvider<SandboxRuntime> runtimes;
    private final JdbcSandboxRepository workspaces;
    private final SandboxProperties properties;
    private final Clock clock;

    @Autowired
    public SandboxReconciliation(ObjectProvider<SandboxRuntime> runtimes, JdbcSandboxRepository workspaces,
                                 SandboxProperties properties) {
        this(runtimes, workspaces, properties, Clock.systemUTC());
    }

    SandboxReconciliation(ObjectProvider<SandboxRuntime> runtimes, JdbcSandboxRepository workspaces,
                          SandboxProperties properties, Clock clock) {
        this.runtimes = runtimes;
        this.workspaces = workspaces;
        this.properties = properties;
        this.clock = clock;
    }

    public void sweep() {
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
