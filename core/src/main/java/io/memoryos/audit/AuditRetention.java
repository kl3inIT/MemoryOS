package io.memoryos.audit;

import io.memoryos.audit.persistence.JdbcAuditEventRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes audit events older than the retention period ({@code memoryos.audit.retention}, 365 days by default). It is the
 * one path the append-only trigger lets delete, and it announces itself to the trigger for its own transaction only.
 */
@Service
public class AuditRetention {
    public static final int BATCH = 5_000;

    private final JdbcAuditEventRepository events;
    private final Duration retention;

    public AuditRetention(JdbcAuditEventRepository events, @Value("${memoryos.audit.retention:P365D}") Duration retention) {
        if (retention.compareTo(Duration.ofDays(1)) < 0) throw new IllegalArgumentException("Audit retention must be at least a day");
        this.events = events;
        this.retention = retention;
    }

    /** Deletes one batch of expired events; returns how many, so a caller may repeat until none remain. */
    @Transactional
    public int sweep() {
        return events.deleteOlderThan(Instant.now().minus(retention), BATCH);
    }
}
