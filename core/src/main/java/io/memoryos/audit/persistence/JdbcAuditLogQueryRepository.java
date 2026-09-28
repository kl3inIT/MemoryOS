package io.memoryos.audit.persistence;

import io.memoryos.shared.LikePattern;
import io.memoryos.audit.AuditEventClass;
import io.memoryos.audit.AuditLog;
import io.memoryos.audit.AuditOutcome;
import io.memoryos.shared.ActorId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Reads one Tenant's audit stream, newest first, keyed by {@code (occurred_at, id)}. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcAuditLogQueryRepository {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcClient jdbc;

    public JdbcAuditLogQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AuditLog.Event> find(UUID tenant, UUID id) {
        return jdbc.sql("SELECT * FROM audit_event WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant).param("id", id).query(JdbcAuditLogQueryRepository::event).optional();
    }

    /** At most {@code limit} events the filters select, strictly after {@code (afterAt, afterId)} when one is given. */
    public List<AuditLog.Event> select(UUID tenant, AuditLog.Query query, @Nullable Instant afterAt, @Nullable UUID afterId,
                                       int limit) {
        return jdbc.sql("""
                SELECT * FROM audit_event
                WHERE tenant_id = :tenant
                  AND (CAST(:from AS timestamptz) IS NULL OR occurred_at >= :from)
                  AND (CAST(:to AS timestamptz) IS NULL OR occurred_at < :to)
                  AND (CAST(:class AS varchar) IS NULL OR event_class = :class)
                  AND (CAST(:action AS varchar) IS NULL OR action = :action)
                  AND (CAST(:outcome AS varchar) IS NULL OR outcome = :outcome)
                  AND (CAST(:actor AS uuid) IS NULL OR actor_id = :actor)
                  AND (CAST(:resourceType AS varchar) IS NULL OR resource_type = :resourceType)
                  AND (CAST(:resourceId AS varchar) IS NULL OR resource_id = :resourceId)
                  AND (CAST(:text AS varchar) IS NULL OR actor_label ILIKE :pattern OR actor_email ILIKE :pattern
                       OR resource_label ILIKE :pattern)
                  AND (CAST(:afterAt AS timestamptz) IS NULL OR (occurred_at, id) < (:afterAt, :afterId))
                ORDER BY occurred_at DESC, id DESC
                LIMIT :limit
                """)
                .param("tenant", tenant)
                .param("from", query.from() == null ? null : Timestamp.from(query.from()), Types.TIMESTAMP)
                .param("to", query.to() == null ? null : Timestamp.from(query.to()), Types.TIMESTAMP)
                .param("class", query.eventClass() == null ? null : query.eventClass().name(), Types.VARCHAR)
                .param("action", query.action(), Types.VARCHAR)
                .param("outcome", query.outcome() == null ? null : query.outcome().name(), Types.VARCHAR)
                .param("actor", query.actor() == null ? null : query.actor().value(), Types.OTHER)
                .param("resourceType", query.resourceType(), Types.VARCHAR)
                .param("resourceId", query.resourceId(), Types.VARCHAR)
                .param("text", query.text(), Types.VARCHAR)
                .param("pattern", query.text() == null ? null : LikePattern.containing(query.text()), Types.VARCHAR)
                .param("afterAt", afterAt == null ? null : Timestamp.from(afterAt), Types.TIMESTAMP)
                .param("afterId", afterId, Types.OTHER)
                .param("limit", limit)
                .query(JdbcAuditLogQueryRepository::event).list();
    }

    @SuppressWarnings("unchecked")
    private static AuditLog.Event event(ResultSet r, int ignored) throws SQLException {
        Map<String, Object> details;
        try {
            details = JSON.readValue(r.getString("details"), Map.class);
        } catch (RuntimeException unreadable) {
            details = Map.of();
        }
        return new AuditLog.Event(r.getObject("id", UUID.class), r.getTimestamp("occurred_at").toInstant(),
                r.getString("action"), AuditEventClass.valueOf(r.getString("event_class")),
                AuditOutcome.valueOf(r.getString("outcome")), actor(r.getObject("actor_id", UUID.class)),
                r.getString("actor_label"), r.getString("actor_email"), r.getString("resource_type"),
                r.getString("resource_id"), r.getString("resource_label"), details, r.getString("trace_id"),
                r.getString("endpoint"), r.getString("source_ip"));
    }

    private static @Nullable ActorId actor(@Nullable UUID id) {
        return id == null ? null : new ActorId(id);
    }
}
