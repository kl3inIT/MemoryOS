package io.memoryos.usage.persistence;

import io.memoryos.usage.report.UsageReport;
import io.memoryos.usage.report.UsageReportStatus;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Generated usage reports and the rows they are built from; every read is bounded by Tenant. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class UsageReportRepository {
    private final JdbcClient jdbc;

    public UsageReportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Claim(UUID id, UUID tenant, LocalDate from, LocalDate to, int attempts)
            implements io.memoryos.shared.LeasedJob.Claim {}

    /** One rollup row as exported: a person (or system work), a UTC day, a task, a provider and model, a boundary. */
    public record ExportRow(LocalDate day, @Nullable UUID actor, @Nullable String email, @Nullable String name,
                            List<String> groups, String flow, String provider, String model, @Nullable String boundary,
                            long calls, long unknownCostCalls, long inputTokens, long outputTokens, long cacheReadTokens,
                            long imageCount, BigDecimal audioSeconds, BigDecimal cost) {}

    public record Member(UUID actor, @Nullable String email, @Nullable String name, String status, List<String> groups) {}

    private static final String REPORT_COLUMNS = """
            r.id, r.requested_by, COALESCE(p.display_name, p.email, CAST(r.requested_by AS varchar)) AS requester,
            r.period_from, r.period_to, r.status, r.size_bytes, r.has_pdf, r.failure, r.created_at, r.finished_at, r.object_key
            """;

    public UsageReport insert(UUID tenant, UUID id, UUID requestedBy, LocalDate from, LocalDate to) {
        jdbc.sql("""
                INSERT INTO ai_usage_report(id, tenant_id, requested_by, period_from, period_to)
                VALUES (:id, :tenant, :actor, :from, :to)
                """).param("id", id).param("tenant", tenant).param("actor", requestedBy).param("from", from).param("to", to)
                .update();
        return find(tenant, id).orElseThrow();
    }

    public List<UsageReport> list(UUID tenant, int limit) {
        return jdbc.sql("SELECT " + REPORT_COLUMNS + """
                FROM ai_usage_report r LEFT JOIN actor_profiles p ON p.actor_id = r.requested_by
                WHERE r.tenant_id = :tenant ORDER BY r.created_at DESC, r.id LIMIT :limit
                """).param("tenant", tenant).param("limit", limit).query(UsageReportRepository::report).list();
    }

    public Optional<UsageReport> find(UUID tenant, UUID id) {
        return jdbc.sql("SELECT " + REPORT_COLUMNS + """
                FROM ai_usage_report r LEFT JOIN actor_profiles p ON p.actor_id = r.requested_by
                WHERE r.tenant_id = :tenant AND r.id = :id
                """).param("tenant", tenant).param("id", id).query(UsageReportRepository::report).optional();
    }

    /**
     * Takes the oldest pending report, or one whose previous lease lapsed, and leases it to this Worker. The attempt
     * count rises on every claim, so a report that keeps killing its Worker stops being retried.
     */
    public Optional<Claim> claim(Duration lease, int maxAttempts) {
        return jdbc.sql("""
                UPDATE ai_usage_report r SET status = 'RUNNING', attempts = r.attempts + 1,
                       lease_until = now() + make_interval(secs => :lease)
                WHERE r.id = (
                    SELECT id FROM ai_usage_report
                    WHERE (status = 'PENDING' OR (status = 'RUNNING' AND lease_until < now())) AND attempts < :max
                    ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING r.id, r.tenant_id, r.period_from, r.period_to, r.attempts
                """).param("lease", lease.toSeconds()).param("max", maxAttempts)
                .query((r, ignored) -> new Claim(r.getObject("id", UUID.class), r.getObject("tenant_id", UUID.class),
                        r.getObject("period_from", LocalDate.class), r.getObject("period_to", LocalDate.class), r.getInt("attempts")))
                .optional();
    }

    /** Reports whose last permitted attempt ran out of lease without finishing. */
    public int failAbandoned(int maxAttempts) {
        return jdbc.sql("""
                UPDATE ai_usage_report SET status = 'FAILED', lease_until = NULL, finished_at = now(),
                       failure = 'The report could not be generated.'
                WHERE status = 'RUNNING' AND lease_until < now() AND attempts >= :max
                """).param("max", maxAttempts).update();
    }

    /** False when the lease was lost to another Worker, which then owns the outcome. */
    public boolean markReady(UUID tenant, UUID id, int attempt, UUID objectId, String objectKey, long size, boolean hasPdf) {
        return jdbc.sql("""
                UPDATE ai_usage_report SET status = 'READY', lease_until = NULL, finished_at = now(), failure = NULL,
                       stored_object_id = :object, object_key = :key, size_bytes = :size, has_pdf = :pdf
                WHERE tenant_id = :tenant AND id = :id AND status = 'RUNNING' AND attempts = :attempt
                """).param("tenant", tenant).param("id", id).param("attempt", attempt).param("object", objectId)
                .param("key", objectKey).param("size", size).param("pdf", hasPdf).update() == 1;
    }

    /** A failure on an attempt that may still be retried returns the report to the queue. */
    public void markFailed(UUID tenant, UUID id, int attempt, int maxAttempts, String failure) {
        jdbc.sql("""
                UPDATE ai_usage_report SET
                       status = CASE WHEN attempts >= :max THEN 'FAILED' ELSE 'PENDING' END,
                       finished_at = CASE WHEN attempts >= :max THEN now() END,
                       failure = CASE WHEN attempts >= :max THEN :failure END,
                       lease_until = NULL
                WHERE tenant_id = :tenant AND id = :id AND status = 'RUNNING' AND attempts = :attempt
                """).param("tenant", tenant).param("id", id).param("attempt", attempt).param("max", maxAttempts)
                .param("failure", failure).update();
    }

    public String tenantName(UUID tenant) {
        return jdbc.sql("SELECT display_name FROM tenants WHERE id = :tenant").param("tenant", tenant)
                .query(String.class).single();
    }

    /**
     * Streams the period's rollup rows in day order. A person's Groups are their current memberships, as on the AI
     * costs page, so a person who moved Group is reported under the Group they are in now.
     */
    public void exportRows(UUID tenant, LocalDate from, LocalDate to, Consumer<ExportRow> consumer) {
        jdbc.sql("""
                SELECT u.day, u.actor_id, p.email, p.display_name,
                       ARRAY(SELECT g.name FROM iam_group_memberships m
                             JOIN iam_groups g ON g.tenant_id = m.tenant_id AND g.id = m.group_id
                             WHERE m.tenant_id = u.tenant_id AND m.actor_id = u.actor_id ORDER BY g.name) AS groups,
                       u.flow, u.provider_name, u.model_name, u.data_boundary, u.calls, u.unknown_cost_calls,
                       u.input_tokens, u.output_tokens, u.cache_read_tokens, u.image_count, u.audio_seconds, u.cost_usd
                FROM ai_usage u LEFT JOIN actor_profiles p ON p.actor_id = u.actor_id
                WHERE u.tenant_id = :tenant AND u.day BETWEEN :from AND :to
                ORDER BY u.day, p.email NULLS LAST, u.actor_id, u.flow, u.model_name, u.provider_name, u.data_boundary
                """).param("tenant", tenant).param("from", from).param("to", to)
                .query((ResultSet r) -> {
                    consumer.accept(new ExportRow(r.getObject("day", LocalDate.class), r.getObject("actor_id", UUID.class),
                            r.getString("email"), r.getString("display_name"), strings(r.getArray("groups")), r.getString("flow"),
                            r.getString("provider_name"), r.getString("model_name"), r.getString("data_boundary"),
                            r.getLong("calls"), r.getLong("unknown_cost_calls"), r.getLong("input_tokens"),
                            r.getLong("output_tokens"), r.getLong("cache_read_tokens"), r.getLong("image_count"),
                            r.getBigDecimal("audio_seconds"), r.getBigDecimal("cost_usd")));
                });
    }

    /** Every member of the Tenant, active or not, with their current Groups. */
    public List<Member> members(UUID tenant) {
        return jdbc.sql("""
                SELECT m.actor_id, p.email, p.display_name, m.status,
                       ARRAY(SELECT g.name FROM iam_group_memberships gm
                             JOIN iam_groups g ON g.tenant_id = gm.tenant_id AND g.id = gm.group_id
                             WHERE gm.tenant_id = m.tenant_id AND gm.actor_id = m.actor_id ORDER BY g.name) AS groups
                FROM tenant_memberships m LEFT JOIN actor_profiles p ON p.actor_id = m.actor_id
                WHERE m.tenant_id = :tenant ORDER BY p.email NULLS LAST, m.actor_id
                """).param("tenant", tenant)
                .query((r, ignored) -> new Member(r.getObject("actor_id", UUID.class), r.getString("email"),
                        r.getString("display_name"), r.getString("status"), strings(r.getArray("groups"))))
                .list();
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        if (array == null) return List.of();
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }

    private static UsageReport report(ResultSet r, int ignored) throws SQLException {
        Long size = r.getObject("size_bytes", Long.class);
        return new UsageReport(r.getObject("id", UUID.class), r.getObject("requested_by", UUID.class), r.getString("requester"),
                r.getObject("period_from", LocalDate.class), r.getObject("period_to", LocalDate.class),
                UsageReportStatus.valueOf(r.getString("status")), size, r.getBoolean("has_pdf"),
                r.getString("failure"), r.getTimestamp("created_at").toInstant(),
                r.getTimestamp("finished_at") == null ? null : r.getTimestamp("finished_at").toInstant(),
                r.getString("object_key"));
    }
}
