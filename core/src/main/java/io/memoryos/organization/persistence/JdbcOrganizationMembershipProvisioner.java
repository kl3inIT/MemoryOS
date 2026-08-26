package io.memoryos.organization.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.organization.InvitationAuthority;
import io.memoryos.organization.InvitationTarget;
import io.memoryos.organization.OrganizationId;
import io.memoryos.organization.OrganizationMembershipProvisioner;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcOrganizationMembershipProvisioner implements OrganizationMembershipProvisioner {

    private static final String SELECT_OWNER_AUTHORITY = """
            SELECT organization.id AS organization_id,
                   organization.display_name AS organization_display_name
            FROM organization_memberships membership
            JOIN organizations organization
              ON organization.id = membership.organization_id
            WHERE membership.actor_id = :actorId
              AND membership.role = 'OWNER'
              AND membership.status = 'ACTIVE'
              AND organization.status = 'ACTIVE'
            """;

    private static final String SELECT_ACTIVE_TARGET = """
            SELECT organization.id AS organization_id,
                   organization.display_name AS organization_display_name
            FROM organizations organization
            WHERE organization.id = :organizationId
              AND organization.status = 'ACTIVE'
            """;

    private static final String COUNT_MEMBERSHIPS = """
            SELECT COUNT(*) FROM organization_memberships WHERE actor_id = :actorId
            """;

    private static final String INSERT_ORGANIZATION_MEMBER = """
            INSERT INTO organization_memberships (organization_id, actor_id, role, status)
            VALUES (:organizationId, :actorId, 'MEMBER', 'ACTIVE')
            """;


    private final JdbcClient jdbcClient;

    public JdbcOrganizationMembershipProvisioner(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public Optional<InvitationAuthority> findInvitationAuthority(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        List<InvitationAuthority> authorities = jdbcClient.sql(SELECT_OWNER_AUTHORITY)
                .param("actorId", actorId.value())
                .query((resultSet, ignored) -> new InvitationAuthority(
                        new OrganizationId(resultSet.getObject("organization_id", UUID.class)),
                        resultSet.getString("organization_display_name")
                ))
                .list();
        if (authorities.size() > 1) {
            throw new IllegalStateException("actor has ambiguous Organization owner authority");
        }
        return authorities.stream().findFirst();
    }

    @Override
    public Optional<InvitationTarget> findActiveInvitationTarget(OrganizationId organizationId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        return jdbcClient.sql(SELECT_ACTIVE_TARGET)
                .param("organizationId", organizationId.value())
                .query((resultSet, ignored) -> new InvitationTarget(
                        new OrganizationId(resultSet.getObject("organization_id", UUID.class)),
                        resultSet.getString("organization_display_name")
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
    public void grantMember(OrganizationId organizationId, ActorId actorId) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        int updated = jdbcClient.sql(INSERT_ORGANIZATION_MEMBER)
                .param("organizationId", organizationId.value())
                .param("actorId", actorId.value())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("grant Organization member affected " + updated + " rows");
        }
    }

}
