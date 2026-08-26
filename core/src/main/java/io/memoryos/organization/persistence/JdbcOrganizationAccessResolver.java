package io.memoryos.organization.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.organization.OrganizationAccessResolver;
import io.memoryos.organization.OrganizationId;
import io.memoryos.organization.OrganizationMembershipRole;
import io.memoryos.organization.OrganizationSessionAuthority;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcOrganizationAccessResolver implements OrganizationAccessResolver {

    private static final String COUNT_ACTIVE_ORGANIZATIONS = """
            SELECT COUNT(*)
            FROM organization_memberships membership
            JOIN organizations organization
              ON organization.id = membership.organization_id
            WHERE membership.actor_id = :actorId
              AND membership.status = 'ACTIVE'
              AND organization.status = 'ACTIVE'
            """;

    private static final String SELECT_SESSION_AUTHORITY = """
            SELECT organization.id AS organization_id,
                   organization.display_name AS organization_display_name,
                   membership.role
            FROM organization_memberships membership
            JOIN organizations organization
              ON organization.id = membership.organization_id
            WHERE membership.actor_id = :actorId
              AND membership.status = 'ACTIVE'
              AND organization.status = 'ACTIVE'
            """;

    private final JdbcClient jdbcClient;

    public JdbcOrganizationAccessResolver(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public boolean hasActiveOrganization(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        return jdbcClient.sql(COUNT_ACTIVE_ORGANIZATIONS)
                .param("actorId", actorId.value())
                .query(Long.class)
                .single() != 0;
    }

    @Override
    public Optional<OrganizationSessionAuthority> findSessionAuthority(ActorId actorId) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        var authorities = jdbcClient.sql(SELECT_SESSION_AUTHORITY)
                .param("actorId", actorId.value())
                .query((resultSet, ignored) -> new OrganizationSessionAuthority(
                        new OrganizationId(resultSet.getObject("organization_id", UUID.class)),
                        resultSet.getString("organization_display_name"),
                        switch (resultSet.getString("role")) {
                            case "OWNER" -> OrganizationMembershipRole.OWNER;
                            case "MEMBER" -> OrganizationMembershipRole.MEMBER;
                            default -> throw new IllegalStateException("unsupported Organization membership role");
                        }
                ))
                .list();
        if (authorities.size() > 1) {
            throw new IllegalStateException("actor belongs to more than one active Organization");
        }
        return authorities.stream().findFirst();
    }
}
