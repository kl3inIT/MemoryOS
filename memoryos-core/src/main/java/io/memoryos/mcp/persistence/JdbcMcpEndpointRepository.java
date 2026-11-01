package io.memoryos.mcp.persistence;

import io.memoryos.shared.TenantId;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** MEM-114: the per-Tenant MCP endpoint switch. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcMcpEndpointRepository {
    private final JdbcClient jdbc;

    public JdbcMcpEndpointRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Setting(boolean enabled, long revision) {}

    public Optional<Setting> setting(TenantId tenant) {
        return jdbc.sql("SELECT enabled, revision FROM mcp_endpoint_setting WHERE tenant_id = :tenant")
                .param("tenant", tenant.value())
                .query((row, ignored) -> new Setting(row.getBoolean("enabled"), row.getLong("revision"))).optional();
    }

    /** The caller holds the Tenant's exclusive IAM lock and has checked the expected revision. */
    public Setting save(TenantId tenant, boolean enabled) {
        return jdbc.sql("""
                INSERT INTO mcp_endpoint_setting(tenant_id, enabled, revision) VALUES(:tenant, :enabled, 1)
                ON CONFLICT (tenant_id) DO UPDATE SET enabled = EXCLUDED.enabled,
                    revision = mcp_endpoint_setting.revision + 1, updated_at = now()
                RETURNING enabled, revision
                """).param("tenant", tenant.value()).param("enabled", enabled)
                .query((row, ignored) -> new Setting(row.getBoolean("enabled"), row.getLong("revision"))).single();
    }
}
