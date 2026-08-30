package io.memoryos.tenant.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.tenant.TenantAccessResolver;
import io.memoryos.tenant.TenantId;
import io.memoryos.tenant.TenantMembershipRole;
import io.memoryos.tenant.TenantSessionAuthority;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcTenantAccessResolver implements TenantAccessResolver {

    private static final String COUNT_ACTIVE_TENANTS = """
            SELECT COUNT(*)
            FROM tenant_memberships membership
            JOIN tenants tenant
              ON tenant.id = membership.tenant_id
            WHERE membership.actor_id = :actorId
              AND membership.status = 'ACTIVE'
              AND tenant.status = 'ACTIVE'
            """;

    private static final String COUNT_ACTIVE_TENANT = """
            SELECT COUNT(*)
            FROM tenants
            WHERE id = :tenantId
              AND status = 'ACTIVE'
            """;

    private static final String SELECT_SESSION_AUTHORITY = """
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
    public boolean hasActiveTenant(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        return jdbcClient.sql(COUNT_ACTIVE_TENANTS)
                .param("actorId", actorId.value())
                .query(Long.class)
                .single() != 0;
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
    public Optional<TenantSessionAuthority> findSessionAuthority(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        var authorities = jdbcClient.sql(SELECT_SESSION_AUTHORITY)
                .param("actorId", actorId.value())
                .query((resultSet, ignored) -> new TenantSessionAuthority(
                        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                        resultSet.getString("tenant_display_name"),
                        switch (resultSet.getString("role")) {
                            case "OWNER" -> TenantMembershipRole.OWNER;
                            case "MEMBER" -> TenantMembershipRole.MEMBER;
                            default -> throw new IllegalStateException("unsupported Tenant membership role");
                        }
                ))
                .list();
        if (authorities.size() > 1) {
            throw new IllegalStateException("actor belongs to more than one active Tenant");
        }
        return authorities.stream().findFirst();
    }
}
