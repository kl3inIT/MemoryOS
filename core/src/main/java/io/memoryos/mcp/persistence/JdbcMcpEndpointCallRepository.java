package io.memoryos.mcp.persistence;

import io.memoryos.shared.LikePattern;
import io.memoryos.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** MEM-209: the MCP endpoint's activity log, read newest first by an {@code (occurred_at, id)} keyset. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcMcpEndpointCallRepository {
    private final JdbcClient jdbc;

    public JdbcMcpEndpointCallRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(UUID id, Instant occurredAt, UUID actorId, @Nullable String actorName, @Nullable String actorEmail,
                      String clientId, String clientKind, String tool, String outcome) {}

    public record Filter(@Nullable Instant from, @Nullable Instant to, @Nullable String clientKind,
                         @Nullable String tool, @Nullable String outcome, @Nullable String person) {}

    public record Cursor(Instant occurredAt, UUID id) {}

    public record Totals(long calls, long users, long failed, long rateLimited) {}

    public record Group(String key, long calls, long users) {}

    public record Day(LocalDate day, long calls, long users) {}

    public void insert(TenantId tenant, UUID actor, String clientId, String clientKind, String tool, String outcome,
                       Instant occurredAt) {
        jdbc.sql("""
                INSERT INTO mcp_endpoint_calls(id, tenant_id, actor_id, client_id, client_kind, tool, outcome, occurred_at)
                VALUES(:id, :tenant, :actor, :client, :kind, :tool, :outcome, :at)
                """).param("id", UUID.randomUUID()).param("tenant", tenant.value()).param("actor", actor)
                .param("client", clientId).param("kind", clientKind).param("tool", tool).param("outcome", outcome)
                .param("at", time(occurredAt)).update();
    }

    /** At most {@code limit} rows older than {@code after}, newest first, with the person's latest observed name. */
    public List<Row> page(TenantId tenant, Filter filter, @Nullable Cursor after, int limit) {
        return jdbc.sql("""
                SELECT c.id, c.occurred_at, c.actor_id, p.display_name, p.email, c.client_id, c.client_kind, c.tool,
                       c.outcome
                FROM mcp_endpoint_calls c LEFT JOIN actor_profiles p ON p.actor_id = c.actor_id
                WHERE c.tenant_id = :tenant
                  AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR c.occurred_at >= CAST(:from AS TIMESTAMPTZ))
                  AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR c.occurred_at < CAST(:to AS TIMESTAMPTZ))
                  AND (CAST(:kind AS VARCHAR) IS NULL OR c.client_kind = CAST(:kind AS VARCHAR))
                  AND (CAST(:tool AS VARCHAR) IS NULL OR c.tool = CAST(:tool AS VARCHAR))
                  AND (CAST(:outcome AS VARCHAR) IS NULL OR c.outcome = CAST(:outcome AS VARCHAR))
                  AND (CAST(:person AS VARCHAR) IS NULL OR p.display_name ILIKE :pattern OR p.email ILIKE :pattern)
                  AND (CAST(:afterAt AS TIMESTAMPTZ) IS NULL
                       OR (c.occurred_at, c.id) < (CAST(:afterAt AS TIMESTAMPTZ), CAST(:afterId AS UUID)))
                ORDER BY c.occurred_at DESC, c.id DESC
                LIMIT :limit
                """).param("tenant", tenant.value())
                .param("from", time(filter.from()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("to", time(filter.to()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("kind", filter.clientKind(), Types.VARCHAR).param("tool", filter.tool(), Types.VARCHAR)
                .param("outcome", filter.outcome(), Types.VARCHAR)
                .param("person", filter.person(), Types.VARCHAR)
                .param("pattern", filter.person() == null ? null : LikePattern.containing(filter.person()), Types.VARCHAR)
                .param("afterAt", after == null ? null : time(after.occurredAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("afterId", after == null ? null : after.id(), Types.OTHER)
                .param("limit", limit).query(JdbcMcpEndpointCallRepository::row).list();
    }

    public Totals totals(TenantId tenant, Instant since) {
        return jdbc.sql("""
                SELECT count(*) AS calls, count(DISTINCT actor_id) AS users,
                       count(*) FILTER (WHERE outcome = 'FAILED') AS failed,
                       count(*) FILTER (WHERE outcome = 'RATE_LIMITED') AS limited
                FROM mcp_endpoint_calls WHERE tenant_id = :tenant AND occurred_at >= :since
                """).param("tenant", tenant.value()).param("since", time(since))
                .query((row, ignored) -> new Totals(row.getLong("calls"), row.getLong("users"), row.getLong("failed"),
                        row.getLong("limited"))).single();
    }

    /** Calls and distinct people per value of {@code column}, most calls first. */
    public List<Group> byClient(TenantId tenant, Instant since) {
        return groups("client_id", tenant, since);
    }

    public List<Group> byTool(TenantId tenant, Instant since) {
        return groups("tool", tenant, since);
    }

    private List<Group> groups(String column, TenantId tenant, Instant since) {
        return jdbc.sql("SELECT " + column + " AS key, count(*) AS calls, count(DISTINCT actor_id) AS users "
                        + "FROM mcp_endpoint_calls WHERE tenant_id = :tenant AND occurred_at >= :since "
                        + "GROUP BY " + column + " ORDER BY calls DESC, key LIMIT 50")
                .param("tenant", tenant.value()).param("since", time(since))
                .query((row, ignored) -> new Group(row.getString("key"), row.getLong("calls"), row.getLong("users")))
                .list();
    }

    /** One entry per UTC day that had a call. */
    public List<Day> byDay(TenantId tenant, Instant since) {
        return jdbc.sql("""
                SELECT (occurred_at AT TIME ZONE 'UTC')::date AS day, count(*) AS calls, count(DISTINCT actor_id) AS users
                FROM mcp_endpoint_calls WHERE tenant_id = :tenant AND occurred_at >= :since
                GROUP BY day ORDER BY day
                """).param("tenant", tenant.value()).param("since", time(since))
                .query((row, ignored) -> new Day(row.getObject("day", LocalDate.class), row.getLong("calls"),
                        row.getLong("users"))).list();
    }

    /** Removes up to {@code limit} calls older than {@code before}; the caller repeats while a full batch went. */
    public int purge(Instant before, int limit) {
        return jdbc.sql("""
                DELETE FROM mcp_endpoint_calls WHERE id IN (
                    SELECT id FROM mcp_endpoint_calls WHERE occurred_at < :before ORDER BY occurred_at LIMIT :limit)
                """).param("before", time(before)).param("limit", limit).update();
    }

    private static Row row(ResultSet row, int ignored) throws SQLException {
        return new Row(row.getObject("id", UUID.class), row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                row.getObject("actor_id", UUID.class), row.getString("display_name"), row.getString("email"),
                row.getString("client_id"), row.getString("client_kind"), row.getString("tool"), row.getString("outcome"));
    }

    private static @Nullable OffsetDateTime time(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
