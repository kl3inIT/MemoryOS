package io.memoryos.usage.persistence;

import io.memoryos.usage.AiUsageLimit;
import io.memoryos.usage.AiUsageLimitScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The configured spending limits, and the day-by-day spend each one is measured against. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class AiUsageLimitRepository {
    private final JdbcClient jdbc;

    public AiUsageLimitRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** One UTC day of spend, newest last. Tokens exclude cache reads, as Onyx's token budget does. */
    public record DaySpend(LocalDate day, long tokens, BigDecimal cost) {}

    private static final String SELECT = """
            SELECT l.id, l.scope, l.group_id, g.name AS group_name, l.token_budget, l.cost_budget_usd,
                   l.period_days, l.enabled
            FROM ai_usage_limit l
            LEFT JOIN iam_groups g ON g.tenant_id = l.tenant_id AND g.id = l.group_id
            WHERE l.tenant_id = :tenant
            """;

    public List<AiUsageLimit> list(UUID tenant) {
        return jdbc.sql(SELECT + " ORDER BY l.scope, g.name NULLS FIRST").param("tenant", tenant)
                .query(AiUsageLimitRepository::limit).list();
    }

    public Optional<AiUsageLimit> find(UUID tenant, UUID id) {
        return jdbc.sql(SELECT + " AND l.id = :id").param("tenant", tenant).param("id", id)
                .query(AiUsageLimitRepository::limit).optional();
    }

    /** Only the limits that are switched on; a limit kept but disabled never refuses a turn. */
    public List<AiUsageLimit> enabled(UUID tenant) {
        return jdbc.sql(SELECT + " AND l.enabled ORDER BY l.scope").param("tenant", tenant)
                .query(AiUsageLimitRepository::limit).list();
    }

    /** The short circuit every chat turn runs: a Tenant with no limit never reads the ledger. */
    public boolean anyEnabled(UUID tenant) {
        return jdbc.sql("SELECT 1 FROM ai_usage_limit WHERE tenant_id = :tenant AND enabled LIMIT 1")
                .param("tenant", tenant).query(Integer.class).optional().isPresent();
    }

    public void insert(UUID tenant, AiUsageLimit limit) {
        jdbc.sql("""
                        INSERT INTO ai_usage_limit(id, tenant_id, scope, group_id, token_budget, cost_budget_usd,
                                                   period_days, enabled)
                        VALUES (:id, :tenant, :scope, :group, :tokens, :cost, :days, :enabled)
                        """)
                .param("id", limit.id()).param("tenant", tenant).param("scope", limit.scope().name())
                .param("group", limit.groupId(), Types.OTHER).param("tokens", limit.tokenBudget(), Types.BIGINT)
                .param("cost", limit.costBudgetUsd(), Types.NUMERIC).param("days", limit.periodDays())
                .param("enabled", limit.enabled()).update();
    }

    public void update(UUID tenant, AiUsageLimit limit) {
        jdbc.sql("""
                        UPDATE ai_usage_limit SET token_budget = :tokens, cost_budget_usd = :cost, period_days = :days,
                               enabled = :enabled, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", tenant).param("id", limit.id()).param("tokens", limit.tokenBudget(), Types.BIGINT)
                .param("cost", limit.costBudgetUsd(), Types.NUMERIC).param("days", limit.periodDays())
                .param("enabled", limit.enabled()).update();
    }

    public boolean delete(UUID tenant, UUID id) {
        return jdbc.sql("DELETE FROM ai_usage_limit WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant).param("id", id).update() > 0;
    }

    /** The Groups this person belongs to, so only their Group limits are weighed. */
    public List<UUID> groupsOf(UUID tenant, UUID actor) {
        return jdbc.sql("SELECT group_id FROM iam_group_memberships WHERE tenant_id = :tenant AND actor_id = :actor")
                .param("tenant", tenant).param("actor", actor).query(UUID.class).list();
    }

    /**
     * Spend per UTC day since {@code from}, for the whole Tenant, one person, or one Group. System work carries no
     * actor and is excluded everywhere: indexing a Source must never exhaust anyone's budget.
     */
    public List<DaySpend> spend(UUID tenant, LocalDate from, AiUsageLimitScope scope, @Nullable UUID subject) {
        String sql = switch (scope) {
            case TENANT -> """
                    SELECT u.day, SUM(u.input_tokens + u.output_tokens) AS tokens, SUM(u.cost_usd) AS cost
                    FROM ai_usage u
                    WHERE u.tenant_id = :tenant AND u.day >= :from AND u.actor_id IS NOT NULL
                    GROUP BY u.day ORDER BY u.day""";
            case PERSON -> """
                    SELECT u.day, SUM(u.input_tokens + u.output_tokens) AS tokens, SUM(u.cost_usd) AS cost
                    FROM ai_usage u
                    WHERE u.tenant_id = :tenant AND u.day >= :from AND u.actor_id = :subject
                    GROUP BY u.day ORDER BY u.day""";
            case GROUP -> """
                    SELECT u.day, SUM(u.input_tokens + u.output_tokens) AS tokens, SUM(u.cost_usd) AS cost
                    FROM ai_usage u
                    JOIN iam_group_memberships m ON m.tenant_id = u.tenant_id AND m.actor_id = u.actor_id
                    WHERE u.tenant_id = :tenant AND u.day >= :from AND m.group_id = :subject
                    GROUP BY u.day ORDER BY u.day""";
        };
        return jdbc.sql(sql).param("tenant", tenant).param("from", from).param("subject", subject, Types.OTHER)
                .query((r, ignored) -> new DaySpend(r.getObject("day", LocalDate.class), r.getLong("tokens"),
                        r.getBigDecimal("cost")))
                .list();
    }

    private static AiUsageLimit limit(ResultSet r, int ignored) throws SQLException {
        Long tokens = r.getObject("token_budget", Long.class);
        return new AiUsageLimit(r.getObject("id", UUID.class), AiUsageLimitScope.valueOf(r.getString("scope")),
                r.getObject("group_id", UUID.class), r.getString("group_name"), tokens,
                r.getBigDecimal("cost_budget_usd"), r.getInt("period_days"), r.getBoolean("enabled"));
    }
}
