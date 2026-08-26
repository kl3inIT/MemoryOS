package io.memoryos.organization.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.organization.InitialOrganizationBootstrapRequest;
import io.memoryos.organization.OrganizationId;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcOrganizationBootstrapRepository {

    private static final String LOCK_BOOTSTRAP_STATE = """
            SELECT initial_organization_id
            FROM organization_bootstrap_state
            WHERE id = 1
            FOR UPDATE
            """;

    private static final String COUNT_ORGANIZATIONS = "SELECT COUNT(*) FROM organizations";

    private static final String INSERT_ORGANIZATION = """
            INSERT INTO organizations (id, slug, display_name, status, bootstrap_reference)
            VALUES (:id, :slug, :displayName, 'ACTIVE', :bootstrapReference)
            """;


    private static final String INSERT_ORGANIZATION_OWNER = """
            INSERT INTO organization_memberships (organization_id, actor_id, role, status)
            VALUES (:organizationId, :actorId, 'OWNER', 'ACTIVE')
            """;


    private static final String PUBLISH_INITIAL_ORGANIZATION = """
            UPDATE organization_bootstrap_state
            SET initial_organization_id = :organizationId
            WHERE id = 1
              AND initial_organization_id IS NULL
            """;

    private static final String SELECT_INITIAL_ORGANIZATION = """
            SELECT organization.id AS organization_id,
                   organization.slug AS organization_slug,
                   organization.display_name AS organization_display_name,
                   organization.status AS organization_status,
                   organization.bootstrap_reference,
                   owner.actor_id AS owner_actor_id
            FROM organizations organization
            JOIN organization_memberships owner
              ON owner.organization_id = organization.id
             AND owner.role = 'OWNER'
             AND owner.status = 'ACTIVE'
            WHERE organization.id = :organizationId
            """;


    private final JdbcClient jdbcClient;

    public JdbcOrganizationBootstrapRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<UUID> lockInitialOrganizationId() {
        return jdbcClient.sql(LOCK_BOOTSTRAP_STATE)
                .query(UUID.class)
                .optional();
    }

    public long countOrganizations() {
        return jdbcClient.sql(COUNT_ORGANIZATIONS).query(Long.class).single();
    }

    public int insertOrganization(
            OrganizationId organizationId,
            InitialOrganizationBootstrapRequest request
    ) {
        return jdbcClient.sql(INSERT_ORGANIZATION)
                .param("id", organizationId.value())
                .param("slug", request.organizationSlug())
                .param("displayName", request.organizationDisplayName())
                .param("bootstrapReference", request.operatorChangeReference())
                .update();
    }


    public int insertOrganizationOwner(OrganizationId organizationId, ActorId actorId) {
        return jdbcClient.sql(INSERT_ORGANIZATION_OWNER)
                .param("organizationId", organizationId.value())
                .param("actorId", actorId.value())
                .update();
    }


    public int publishInitialOrganization(OrganizationId organizationId) {
        return jdbcClient.sql(PUBLISH_INITIAL_ORGANIZATION)
                .param("organizationId", organizationId.value())
                .update();
    }

    public Optional<InitialOrganizationRow> findInitialOrganization(UUID organizationId) {
        return jdbcClient.sql(SELECT_INITIAL_ORGANIZATION)
                .param("organizationId", organizationId)
                .query((resultSet, ignored) -> new InitialOrganizationRow(
                        resultSet.getObject("organization_id", UUID.class),
                        resultSet.getString("organization_slug"),
                        resultSet.getString("organization_display_name"),
                        resultSet.getString("organization_status"),
                        resultSet.getString("bootstrap_reference"),
                        resultSet.getObject("owner_actor_id", UUID.class)
                ))
                .optional();
    }

}
