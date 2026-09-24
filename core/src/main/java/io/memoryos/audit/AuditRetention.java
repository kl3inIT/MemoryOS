package io.memoryos.audit;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes audit events older than the retention period ({@code memoryos.audit.retention}, 365 days by default). It is the
 * one path the append-only trigger lets delete, and it announces itself to the trigger for its own transaction only.
 */
@Service
public class AuditRetention {
    public static final int BATCH = 5_000;

    private final JdbcClient jdbc;
    private final Duration retention;

    public AuditRetention(JdbcClient jdbc, @Value("${memoryos.audit.retention:P365D}") Duration retention) {
        if (retention.compareTo(Duration.ofDays(1)) < 0) throw new IllegalArgumentException("Audit retention must be at least a day");
        this.jdbc = jdbc;
        this.retention = retention;
    }

    /** Deletes one batch of expired events; returns how many, so a caller may repeat until none remain. */
    @Transactional
    public int sweep() {
        jdbc.sql("SELECT set_config('memoryos.audit_retention', 'on', true)").query().singleValue();
        return jdbc.sql("""
                DELETE FROM audit_event WHERE id IN (
                    SELECT id FROM audit_event WHERE occurred_at < :cutoff ORDER BY occurred_at LIMIT :batch)
                """).param("cutoff", java.sql.Timestamp.from(Instant.now().minus(retention))).param("batch", BATCH).update();
    }
}
