package io.memoryos.mcp.persistence;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Which MCP servers a User may use: an organization-wide server, or one granted to a Group they belong to.
 * The Group predicate reads IAM membership directly, as the Chat catalog and Source scopes do.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class McpAccessRepository {
    private static final String ACCESSIBLE = """
            server.tenant_wide = TRUE OR EXISTS (
                SELECT 1 FROM mcp_server_group access
                JOIN iam_group_memberships member ON member.tenant_id = access.tenant_id
                  AND member.group_id = access.group_id AND member.actor_id = :actor
                WHERE access.tenant_id = server.tenant_id AND access.server_id = server.id)
            """;

    private final JdbcClient jdbc;

    public McpAccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<UUID> accessibleServerIds(UUID tenantId, UUID actorId) {
        List<UUID> ids = jdbc.sql("SELECT server.id FROM mcp_server server WHERE server.tenant_id = :tenant AND (" + ACCESSIBLE + ")")
                .param("tenant", tenantId).param("actor", actorId).query(UUID.class).list();
        return Set.copyOf(ids);
    }

    public boolean accessible(UUID tenantId, UUID serverId, UUID actorId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM mcp_server server WHERE server.tenant_id = :tenant"
                        + " AND server.id = :server AND (" + ACCESSIBLE + "))")
                .param("tenant", tenantId).param("server", serverId).param("actor", actorId).query(Boolean.class).single();
    }
}
