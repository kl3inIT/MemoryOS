package io.memoryos.mcp.persistence;

import io.memoryos.mcp.McpTrustedApp;
import io.memoryos.shared.TenantId;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** MEM-207: the trusted apps of each Tenant. A built-in app's hosts live in code, so its row keeps empty lists. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcMcpTrustedAppRepository {
    private static final String COLUMNS = "id, preset, name, client_id_hosts, document_hosts, enabled, revision";
    private final JdbcClient jdbc;

    public JdbcMcpTrustedAppRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Gives the Tenant a row for each built-in app it has none for, switched on. */
    public void ensureBuiltIns(TenantId tenant) {
        for (var preset : McpTrustedApp.Preset.builtIns()) {
            jdbc.sql("""
                    INSERT INTO mcp_trusted_apps(id, tenant_id, preset, name, client_id_hosts, document_hosts, enabled, revision)
                    VALUES(:id, :tenant, :preset, :name, '{}', '{}', TRUE, 1)
                    ON CONFLICT (tenant_id, preset) WHERE preset <> 'CUSTOM' DO NOTHING
                    """).param("id", UUID.randomUUID()).param("tenant", tenant.value()).param("preset", preset.name())
                    .param("name", preset.displayName()).update();
        }
    }

    /** Built-in apps first, then the Tenant's own in the order they were added. */
    public List<McpTrustedApp> list(TenantId tenant) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM mcp_trusted_apps WHERE tenant_id = :tenant "
                        + "ORDER BY CASE preset WHEN 'CLAUDE' THEN 0 WHEN 'CHATGPT' THEN 1 ELSE 2 END, created_at, id")
                .param("tenant", tenant.value()).query(JdbcMcpTrustedAppRepository::app).list();
    }

    public Optional<McpTrustedApp> find(TenantId tenant, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM mcp_trusted_apps WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.value()).param("id", id).query(JdbcMcpTrustedAppRepository::app).optional();
    }

    public long customCount(TenantId tenant) {
        return jdbc.sql("SELECT count(*) FROM mcp_trusted_apps WHERE tenant_id = :tenant AND preset = 'CUSTOM'")
                .param("tenant", tenant.value()).query(Long.class).single();
    }

    public McpTrustedApp insertCustom(TenantId tenant, String name, List<String> clientIdHosts, List<String> documentHosts) {
        return jdbc.sql("""
                INSERT INTO mcp_trusted_apps(id, tenant_id, preset, name, client_id_hosts, document_hosts, enabled, revision)
                VALUES(:id, :tenant, 'CUSTOM', :name, CAST(:clientIdHosts AS TEXT[]), CAST(:documentHosts AS TEXT[]), TRUE, 1)
                RETURNING""" + " " + COLUMNS).param("id", UUID.randomUUID()).param("tenant", tenant.value()).param("name", name)
                .param("clientIdHosts", literal(clientIdHosts)).param("documentHosts", literal(documentHosts))
                .query(JdbcMcpTrustedAppRepository::app).single();
    }

    /** Empty when the app is gone or its revision moved on. */
    public Optional<McpTrustedApp> setEnabled(TenantId tenant, UUID id, boolean enabled, long revision) {
        return jdbc.sql("""
                UPDATE mcp_trusted_apps SET enabled = :enabled, revision = revision + 1, updated_at = now()
                WHERE tenant_id = :tenant AND id = :id AND revision = :revision
                RETURNING""" + " " + COLUMNS).param("enabled", enabled).param("tenant", tenant.value()).param("id", id)
                .param("revision", revision).query(JdbcMcpTrustedAppRepository::app).optional();
    }

    /** False when the app is gone, built in, or its revision moved on. */
    public boolean deleteCustom(TenantId tenant, UUID id, long revision) {
        return jdbc.sql("""
                DELETE FROM mcp_trusted_apps
                WHERE tenant_id = :tenant AND id = :id AND revision = :revision AND preset = 'CUSTOM'
                """).param("tenant", tenant.value()).param("id", id).param("revision", revision).update() == 1;
    }

    private static McpTrustedApp app(ResultSet row, int ignored) throws SQLException {
        return new McpTrustedApp(row.getObject("id", UUID.class), McpTrustedApp.Preset.valueOf(row.getString("preset")),
                row.getString("name"), hosts(row.getArray("client_id_hosts")), hosts(row.getArray("document_hosts")),
                row.getBoolean("enabled"), row.getLong("revision"));
    }

    /**
     * A PostgreSQL array literal. JdbcClient expands an array parameter into a list of placeholders, as for IN; the
     * service admits only host names, which hold no comma, quote or brace.
     */
    private static String literal(List<String> hosts) {
        return "{" + String.join(",", hosts) + "}";
    }

    private static List<String> hosts(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }
}
