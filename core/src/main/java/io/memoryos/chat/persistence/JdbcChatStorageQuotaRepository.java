package io.memoryos.chat.persistence;

import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Storage limits: one per Tenant in its Chat settings, and an override per person (MEM-152). */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChatStorageQuotaRepository {
    private final JdbcClient jdbc;

    public JdbcChatStorageQuotaRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** A person's own limit, whoever set it; absent means the Tenant's, and no Tenant limit means none. */
    public record Override(UUID actor, @Nullable String name, long maxBytes) {}

    /** The limit that applies to one person: their override, else the Tenant's, else none. */
    public Optional<Long> limit(TenantId tenant, ActorId actor) {
        return jdbc.sql("""
                SELECT COALESCE(q.max_bytes, s.storage_quota_bytes) AS limit_bytes
                FROM tenant_memberships m
                LEFT JOIN chat_storage_quota q ON q.tenant_id = m.tenant_id AND q.actor_id = m.actor_id
                LEFT JOIN chat_settings s ON s.tenant_id = m.tenant_id
                WHERE m.tenant_id = :tenant AND m.actor_id = :actor
                """).param("tenant", tenant.value()).param("actor", actor.value())
                .query((row, ignored) -> row.getObject("limit_bytes", Long.class)).optional()
                .flatMap(Optional::ofNullable);
    }

    public Optional<Long> tenantLimit(TenantId tenant) {
        return jdbc.sql("SELECT storage_quota_bytes FROM chat_settings WHERE tenant_id = :tenant")
                .param("tenant", tenant.value())
                .query((row, ignored) -> row.getObject("storage_quota_bytes", Long.class)).optional()
                .flatMap(Optional::ofNullable);
    }

    /** Writes the Tenant's limit into its Chat settings row, creating that row if this is the first setting. */
    public void tenantLimit(TenantId tenant, @Nullable Long maxBytes) {
        jdbc.sql("""
                INSERT INTO chat_settings(tenant_id, storage_quota_bytes) VALUES (:tenant, :max)
                ON CONFLICT (tenant_id) DO UPDATE SET storage_quota_bytes = :max
                """).param("tenant", tenant.value()).param("max", maxBytes).update();
    }

    public List<Override> overrides(TenantId tenant, int limit) {
        return jdbc.sql("""
                SELECT q.actor_id, COALESCE(p.display_name, p.email, CAST(q.actor_id AS varchar)) AS name, q.max_bytes
                FROM chat_storage_quota q LEFT JOIN actor_profiles p ON p.actor_id = q.actor_id
                WHERE q.tenant_id = :tenant ORDER BY name, q.actor_id LIMIT :limit
                """).param("tenant", tenant.value()).param("limit", limit)
                .query((row, ignored) -> new Override(row.getObject("actor_id", UUID.class), row.getString("name"),
                        row.getLong("max_bytes")))
                .list();
    }

    /** Sets or clears one person's own limit; a member of another Tenant is not matched, so nothing is written. */
    public boolean override(TenantId tenant, ActorId actor, @Nullable Long maxBytes) {
        if (maxBytes == null) {
            jdbc.sql("DELETE FROM chat_storage_quota WHERE tenant_id = :tenant AND actor_id = :actor")
                    .param("tenant", tenant.value()).param("actor", actor.value()).update();
            return member(tenant, actor);
        }
        return jdbc.sql("""
                INSERT INTO chat_storage_quota(tenant_id, actor_id, max_bytes)
                SELECT :tenant, :actor, :max FROM tenant_memberships m
                WHERE m.tenant_id = :tenant AND m.actor_id = :actor
                ON CONFLICT (tenant_id, actor_id)
                DO UPDATE SET max_bytes = :max, updated_at = CURRENT_TIMESTAMP
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("max", maxBytes).update() == 1;
    }

    private boolean member(TenantId tenant, ActorId actor) {
        return jdbc.sql("SELECT count(*) FROM tenant_memberships WHERE tenant_id = :tenant AND actor_id = :actor")
                .param("tenant", tenant.value()).param("actor", actor.value()).query(Long.class).single() == 1;
    }
}
