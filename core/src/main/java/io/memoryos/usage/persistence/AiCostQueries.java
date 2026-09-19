package io.memoryos.usage.persistence;

import io.memoryos.usage.AiUsageFlow;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only aggregates over the daily AI usage rollup; every query is bounded by Tenant and day range. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class AiCostQueries {
    private final JdbcClient jdbc;

    public AiCostQueries(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Scope(UUID tenant, LocalDate from, LocalDate to, @Nullable UUID actor, @Nullable String model,
                        @Nullable AiUsageFlow flow, boolean systemOnly) {}

    public record Totals(BigDecimal cost, BigDecimal externalCost, long calls, long unknownCostCalls, long inputTokens,
                         long outputTokens, long cacheReadTokens, long imageCount, BigDecimal audioSeconds, long activePeople) {}

    public record Day(LocalDate day, String key, BigDecimal cost, long calls, long inputTokens, long outputTokens,
                      long cacheReadTokens) {}

    public record Row(String key, String label, @Nullable String detail, long calls, long unknownCostCalls, long inputTokens,
                      long outputTokens, BigDecimal cost) {}

    public enum Split { BOUNDARY, MODEL, NONE }

    public enum Dimension { ACTOR, GROUP, MODEL, FLOW, PROVIDER }

    private static final String FILTER = """
            u.tenant_id = :tenant AND u.day BETWEEN :from AND :to
              AND (CAST(:actor AS uuid) IS NULL OR u.actor_id = :actor)
              AND (NOT :systemOnly OR u.actor_id IS NULL)
              AND (CAST(:model AS varchar) IS NULL OR u.model_name = :model)
              AND (CAST(:flow AS varchar) IS NULL OR u.flow = :flow)
            """;

    public Totals totals(Scope scope) {
        return bind(jdbc.sql("""
                SELECT COALESCE(SUM(u.cost_usd), 0) AS cost,
                       COALESCE(SUM(u.cost_usd) FILTER (WHERE u.data_boundary = 'EXTERNAL'), 0) AS external_cost,
                       COALESCE(SUM(u.calls), 0) AS calls, COALESCE(SUM(u.unknown_cost_calls), 0) AS unknown,
                       COALESCE(SUM(u.input_tokens), 0) AS input, COALESCE(SUM(u.output_tokens), 0) AS output,
                       COALESCE(SUM(u.cache_read_tokens), 0) AS cache_read, COALESCE(SUM(u.image_count), 0) AS images,
                       COALESCE(SUM(u.audio_seconds), 0) AS audio, COUNT(DISTINCT u.actor_id) AS people
                FROM ai_usage u WHERE\s""" + FILTER), scope)
                .query((r, ignored) -> new Totals(r.getBigDecimal("cost"), r.getBigDecimal("external_cost"), r.getLong("calls"),
                        r.getLong("unknown"), r.getLong("input"), r.getLong("output"), r.getLong("cache_read"), r.getLong("images"),
                        r.getBigDecimal("audio"), r.getLong("people")))
                .single();
    }

    public List<Day> daily(Scope scope, Split split) {
        // Split keys are internal constants; request values stay bound parameters.
        String key = switch (split) {
            case BOUNDARY -> "COALESCE(u.data_boundary, 'NONE')";
            case MODEL -> "u.model_name";
            case NONE -> "'ALL'";
        };
        return bind(jdbc.sql("SELECT u.day, " + key + " AS series, SUM(u.cost_usd) AS cost, SUM(u.calls) AS calls, "
                + "SUM(u.input_tokens) AS input, SUM(u.output_tokens) AS output, SUM(u.cache_read_tokens) AS cache_read "
                + "FROM ai_usage u WHERE " + FILTER + " GROUP BY u.day, series ORDER BY u.day, series LIMIT 5000"), scope)
                .query((r, ignored) -> new Day(r.getObject("day", LocalDate.class), r.getString("series"), r.getBigDecimal("cost"),
                        r.getLong("calls"), r.getLong("input"), r.getLong("output"), r.getLong("cache_read")))
                .list();
    }

    public List<Row> breakdown(Scope scope, Dimension dimension, int limit) {
        String sql = switch (dimension) {
            case ACTOR -> """
                    SELECT COALESCE(CAST(u.actor_id AS varchar), 'SYSTEM') AS key,
                           COALESCE(MAX(p.display_name), MAX(p.email), CAST(u.actor_id AS varchar), 'SYSTEM') AS label,
                           MAX(p.email) AS detail,""" + SUMS + """
                    FROM ai_usage u LEFT JOIN actor_profiles p ON p.actor_id = u.actor_id
                    WHERE\s""" + FILTER + " GROUP BY u.actor_id";
            // A person counts in every Group they currently belong to, so Group totals can exceed the Tenant total.
            case GROUP -> """
                    SELECT CAST(g.id AS varchar) AS key, MAX(g.name) AS label, CAST(NULL AS varchar) AS detail,""" + SUMS + """
                    FROM ai_usage u
                    JOIN iam_group_memberships m ON m.tenant_id = u.tenant_id AND m.actor_id = u.actor_id
                    JOIN iam_groups g ON g.tenant_id = m.tenant_id AND g.id = m.group_id
                    WHERE\s""" + FILTER + " GROUP BY g.id";
            case MODEL -> "SELECT u.model_name || '|' || u.provider_name AS key, u.model_name AS label, u.provider_name AS detail,"
                    + SUMS + " FROM ai_usage u WHERE " + FILTER + " GROUP BY u.model_name, u.provider_name";
            case FLOW -> "SELECT u.flow AS key, u.flow AS label, CAST(NULL AS varchar) AS detail," + SUMS
                    + " FROM ai_usage u WHERE " + FILTER + " GROUP BY u.flow";
            // Boundary is recorded per call, so a provider relabelled mid-period shows one row per boundary.
            case PROVIDER -> "SELECT u.provider_name || '|' || COALESCE(u.data_boundary, '') AS key, u.provider_name AS label,"
                    + " u.data_boundary AS detail," + SUMS
                    + " FROM ai_usage u WHERE " + FILTER + " GROUP BY u.provider_name, u.data_boundary";
        };
        return bind(jdbc.sql(sql + " ORDER BY cost DESC, calls DESC, key LIMIT :limit"), scope).param("limit", limit)
                .query(AiCostQueries::row).list();
    }

    private static final String SUMS = """
             SUM(u.calls) AS calls, SUM(u.unknown_cost_calls) AS unknown, SUM(u.input_tokens) AS input,
             SUM(u.output_tokens) AS output, SUM(u.cost_usd) AS cost
            """;

    private static Row row(ResultSet r, int ignored) throws SQLException {
        return new Row(r.getString("key"), r.getString("label"), r.getString("detail"), r.getLong("calls"), r.getLong("unknown"),
                r.getLong("input"), r.getLong("output"), r.getBigDecimal("cost"));
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, Scope scope) {
        return statement.param("tenant", scope.tenant()).param("from", scope.from()).param("to", scope.to())
                .param("actor", scope.actor(), Types.OTHER).param("systemOnly", scope.systemOnly())
                .param("model", scope.model(), Types.VARCHAR)
                .param("flow", scope.flow() == null ? null : scope.flow().name(), Types.VARCHAR);
    }
}
