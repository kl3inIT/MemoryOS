package io.memoryos.organization.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.organization.OrganizationAccessResolver;

import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public final class JdbcOrganizationAccessResolver implements OrganizationAccessResolver {

    private static final String COUNT_ACTIVE_ORGANIZATIONS = """
            SELECT COUNT(*)
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
}
