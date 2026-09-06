package io.memoryos.tenant.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.tenant.InvitationTarget;
import io.memoryos.tenant.TenantId;
import io.memoryos.tenant.TenantMembershipProvisioner;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcTenantMembershipProvisioner implements TenantMembershipProvisioner {

    private static final String SELECT_ACTIVE_TARGET = """
            SELECT tenant.id AS tenant_id,
                   tenant.display_name AS tenant_display_name
            FROM tenants tenant
            WHERE tenant.id = :tenantId
              AND tenant.status = 'ACTIVE'
            """;

    private static final String COUNT_MEMBERSHIPS = """
            SELECT COUNT(*) FROM tenant_memberships WHERE actor_id = :actorId
            """;

    private static final String INSERT_TENANT_MEMBER = """
            INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
            VALUES (:tenantId, :actorId, 'MEMBER', 'ACTIVE')
            """;

    private final JdbcClient jdbcClient;

    public JdbcTenantMembershipProvisioner(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public Optional<InvitationTarget> findActiveInvitationTarget(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return jdbcClient.sql(SELECT_ACTIVE_TARGET)
                .param("tenantId", tenantId.value())
                .query((resultSet, ignored) -> new InvitationTarget(
                        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                        resultSet.getString("tenant_display_name")
                ))
                .optional();
    }

    @Override
    public boolean hasAnyMembership(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        return jdbcClient.sql(COUNT_MEMBERSHIPS)
                .param("actorId", actorId.value())
                .query(Long.class)
                .single() != 0;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void grantMember(TenantId tenantId, ActorId actorId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        int updated = jdbcClient.sql(INSERT_TENANT_MEMBER)
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("grant Tenant member affected " + updated + " rows");
        }
    }
}
