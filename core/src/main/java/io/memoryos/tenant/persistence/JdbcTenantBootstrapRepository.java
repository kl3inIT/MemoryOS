package io.memoryos.tenant.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.tenant.InitialTenantBootstrapRequest;
import io.memoryos.tenant.TenantId;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcTenantBootstrapRepository {

    private static final String LOCK_BOOTSTRAP_STATE = """
            SELECT tenant_id
            FROM tenant_bootstrap_state
            WHERE id = 1
            FOR UPDATE
            """;

    private static final String COUNT_TENANTS = "SELECT COUNT(*) FROM tenants";

    private static final String INSERT_TENANT = """
            INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference)
            VALUES (:id, :slug, :displayName, 'ACTIVE', :bootstrapReference)
            """;


    private static final String INSERT_TENANT_OWNER = """
            INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
            VALUES (:tenantId, :actorId, 'OWNER', 'ACTIVE')
            """;


    private static final String PUBLISH_INITIAL_TENANT = """
            UPDATE tenant_bootstrap_state
            SET tenant_id = :tenantId
            WHERE id = 1
              AND tenant_id IS NULL
            """;

    private static final String SELECT_INITIAL_TENANT = """
            SELECT tenant.id AS tenant_id,
                   tenant.slug AS tenant_slug,
                   tenant.display_name AS tenant_display_name,
                   tenant.status AS tenant_status,
                   tenant.bootstrap_reference,
                   owner.actor_id AS owner_actor_id
            FROM tenants tenant
            JOIN tenant_memberships owner
              ON owner.tenant_id = tenant.id
             AND owner.role = 'OWNER'
             AND owner.status = 'ACTIVE'
            WHERE tenant.id = :tenantId
            """;


    private final JdbcClient jdbcClient;

    public JdbcTenantBootstrapRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<UUID> lockInitialTenantId() {
        return jdbcClient.sql(LOCK_BOOTSTRAP_STATE)
                .query(UUID.class)
                .optional();
    }

    public long countTenants() {
        return jdbcClient.sql(COUNT_TENANTS).query(Long.class).single();
    }

    public int insertTenant(InitialTenantBootstrapRequest request) {
        return jdbcClient.sql(INSERT_TENANT)
                .param("id", request.tenantId().value())
                .param("slug", request.tenantSlug())
                .param("displayName", request.tenantDisplayName())
                .param("bootstrapReference", request.operatorChangeReference())
                .update();
    }


    public int insertTenantOwner(TenantId tenantId, ActorId actorId) {
        return jdbcClient.sql(INSERT_TENANT_OWNER)
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .update();
    }


    public int publishInitialTenant(TenantId tenantId) {
        return jdbcClient.sql(PUBLISH_INITIAL_TENANT)
                .param("tenantId", tenantId.value())
                .update();
    }

    public Optional<InitialTenantRow> findInitialTenant(UUID tenantId) {
        return jdbcClient.sql(SELECT_INITIAL_TENANT)
                .param("tenantId", tenantId)
                .query((resultSet, ignored) -> new InitialTenantRow(
                        resultSet.getObject("tenant_id", UUID.class),
                        resultSet.getString("tenant_slug"),
                        resultSet.getString("tenant_display_name"),
                        resultSet.getString("tenant_status"),
                        resultSet.getString("bootstrap_reference"),
                        resultSet.getObject("owner_actor_id", UUID.class)
                ))
                .optional();
    }

}
