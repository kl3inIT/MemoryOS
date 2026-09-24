package io.memoryos.audit.persistence;

import io.memoryos.audit.AuditTrail;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes to the append-only {@code audit_event} table: the insert, the savepoint that keeps a failed insert from
 * aborting the caller's transaction, and the retention delete the append-only trigger lets through.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcAuditEventRepository {
    private static final String SAVEPOINT = "memoryos_audit";

    private final JdbcClient jdbc;

    public JdbcAuditEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row as it is stored; {@code details} is already the JSON the column keeps. */
    public record NewEvent(UUID id, UUID tenant, Instant occurredAt, String action, String eventClass, String outcome,
                           @Nullable UUID actor, @Nullable String actorLabel, @Nullable String actorEmail,
                           @Nullable String resourceType, @Nullable String resourceId, @Nullable String resourceLabel,
                           String details, @Nullable String traceId, @Nullable String endpoint,
                           @Nullable String sourceIp, int schemaVersion) {}

    /** Marks the point a failed insert rolls back to. In PostgreSQL any failed statement aborts the whole transaction. */
    public void savepoint() {
        jdbc.sql("SAVEPOINT " + SAVEPOINT).update();
    }

    public void releaseSavepoint() {
        jdbc.sql("RELEASE SAVEPOINT " + SAVEPOINT).update();
    }

    public void rollbackToSavepoint() {
        jdbc.sql("ROLLBACK TO SAVEPOINT " + SAVEPOINT).update();
    }

    public void insert(NewEvent event) {
        jdbc.sql("""
                INSERT INTO audit_event(id, tenant_id, occurred_at, action, event_class, outcome, actor_id, actor_label,
                    actor_email, resource_type, resource_id, resource_label, details, trace_id, endpoint, source_ip, schema_version)
                VALUES (:id, :tenant, :at, :action, :class, :outcome, :actor, :actorLabel, :actorEmail, :resourceType, :resourceId,
                    :resourceLabel, CAST(:details AS jsonb), :trace, :endpoint, :ip, :version)
                """)
                .param("id", event.id()).param("tenant", event.tenant()).param("at", Timestamp.from(event.occurredAt()))
                .param("action", event.action()).param("class", event.eventClass())
                .param("outcome", event.outcome())
                .param("actor", event.actor(), Types.OTHER)
                .param("actorLabel", event.actorLabel(), Types.VARCHAR)
                .param("actorEmail", event.actorEmail(), Types.VARCHAR)
                .param("resourceType", event.resourceType(), Types.VARCHAR)
                .param("resourceId", event.resourceId(), Types.VARCHAR)
                .param("resourceLabel", event.resourceLabel(), Types.VARCHAR)
                .param("details", event.details())
                .param("trace", event.traceId(), Types.VARCHAR)
                .param("endpoint", event.endpoint(), Types.VARCHAR)
                .param("ip", event.sourceIp(), Types.VARCHAR)
                .param("version", event.schemaVersion())
                .update();
    }

    /**
     * The actor's display name and e-mail as their profile holds them now. Reads IAM's {@code actor_profiles} in the
     * caller's transaction, so it sees a person the same change has just admitted.
     */
    public Optional<AuditTrail.Person> person(UUID actor) {
        return jdbc.sql("""
                SELECT COALESCE(NULLIF(display_name, ''), email, CAST(actor_id AS varchar)) AS label, email
                FROM actor_profiles WHERE actor_id = :actor
                """).param("actor", actor)
                .query((r, ignored) -> new AuditTrail.Person(r.getString("label"), r.getString("email"))).optional();
    }

    /**
     * Deletes up to {@code batch} events older than {@code cutoff}, oldest first. Announces the retention sweep to the
     * append-only trigger for this transaction only; the caller's transaction must be open.
     */
    public int deleteOlderThan(Instant cutoff, int batch) {
        jdbc.sql("SELECT set_config('memoryos.audit_retention', 'on', true)").query().singleValue();
        return jdbc.sql("""
                DELETE FROM audit_event WHERE id IN (
                    SELECT id FROM audit_event WHERE occurred_at < :cutoff ORDER BY occurred_at LIMIT :batch)
                """).param("cutoff", Timestamp.from(cutoff)).param("batch", batch).update();
    }
}
