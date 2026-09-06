package io.memoryos.tenant.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.tenant.TenantAccessResolver;
import io.memoryos.tenant.TenantId;
import io.memoryos.tenant.TenantMembership;
import io.memoryos.tenant.TenantMembershipRole;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcTenantAccessResolver implements TenantAccessResolver {

    private static final String COUNT_ACTIVE_TENANT = """
            SELECT COUNT(*)
            FROM tenants
            WHERE id = :tenantId
              AND status = 'ACTIVE'
            """;

    private static final String SELECT_ACTIVE_MEMBERSHIP = """
            SELECT tenant.id AS tenant_id,
                   tenant.display_name AS tenant_display_name,
                   membership.role
            FROM tenant_memberships membership
            JOIN tenants tenant
              ON tenant.id = membership.tenant_id
            WHERE membership.actor_id = :actorId
              AND membership.status = 'ACTIVE'
              AND tenant.status = 'ACTIVE'
            """;

    private final JdbcClient jdbcClient;

    public JdbcTenantAccessResolver(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public boolean isActiveTenant(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return jdbcClient.sql(COUNT_ACTIVE_TENANT)
                .param("tenantId", tenantId.value())
                .query(Long.class)
                .single() != 0;
    }

    @Override
    public Optional<TenantMembership> findActiveMembership(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        var memberships = jdbcClient.sql(SELECT_ACTIVE_MEMBERSHIP)
                .param("actorId", actorId.value())
                .query((resultSet, ignored) -> new TenantMembership(
                        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                        resultSet.getString("tenant_display_name"),
                        TenantMembershipRole.valueOf(resultSet.getString("role"))
                ))
                .list();
        if (memberships.size() > 1) {
            throw new IllegalStateException("actor belongs to more than one active Tenant");
        }
        return memberships.stream().findFirst();
    }
}
