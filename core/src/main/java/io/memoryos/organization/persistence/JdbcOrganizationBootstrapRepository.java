package io.memoryos.organization.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.organization.InitialOrganizationBootstrapRequest;
import io.memoryos.organization.OrganizationId;
import io.memoryos.organization.WorkspaceId;

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
            INSERT INTO organizations (
                id, slug, display_name, status, default_workspace_id, bootstrap_reference
            )
            VALUES (
                :id, :slug, :displayName, 'ACTIVE', NULL, :bootstrapReference
            )
            """;

    private static final String INSERT_WORKSPACE = """
            INSERT INTO workspaces (id, organization_id, slug, display_name, status)
            VALUES (:id, :organizationId, :slug, :displayName, 'ACTIVE')
            """;

    private static final String SET_DEFAULT_WORKSPACE = """
            UPDATE organizations
            SET default_workspace_id = :workspaceId,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :organizationId
              AND default_workspace_id IS NULL
            """;

    private static final String INSERT_ORGANIZATION_OWNER = """
            INSERT INTO organization_memberships (organization_id, actor_id, role, status)
            VALUES (:organizationId, :actorId, 'OWNER', 'ACTIVE')
            """;

    private static final String INSERT_WORKSPACE_ADMIN = """
            INSERT INTO workspace_memberships (organization_id, workspace_id, actor_id, role, status)
            VALUES (:organizationId, :workspaceId, :actorId, 'ADMIN', 'ACTIVE')
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
                   organization.default_workspace_id,
                   organization.bootstrap_reference,
                   workspace.slug AS workspace_slug,
                   workspace.display_name AS workspace_display_name,
                   workspace.status AS workspace_status,
                   owner.actor_id AS owner_actor_id
            FROM organizations organization
            JOIN workspaces workspace
              ON workspace.organization_id = organization.id
             AND workspace.id = organization.default_workspace_id
            JOIN organization_memberships owner
              ON owner.organization_id = organization.id
             AND owner.role = 'OWNER'
             AND owner.status = 'ACTIVE'
            JOIN workspace_memberships workspace_admin
              ON workspace_admin.organization_id = organization.id
             AND workspace_admin.workspace_id = workspace.id
             AND workspace_admin.actor_id = owner.actor_id
             AND workspace_admin.role = 'ADMIN'
             AND workspace_admin.status = 'ACTIVE'
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

    public int insertWorkspace(
            OrganizationId organizationId,
            WorkspaceId workspaceId,
            InitialOrganizationBootstrapRequest request
    ) {
        return jdbcClient.sql(INSERT_WORKSPACE)
                .param("id", workspaceId.value())
                .param("organizationId", organizationId.value())
                .param("slug", request.defaultWorkspaceSlug())
                .param("displayName", request.defaultWorkspaceDisplayName())
                .update();
    }

    public int setDefaultWorkspace(OrganizationId organizationId, WorkspaceId workspaceId) {
        return jdbcClient.sql(SET_DEFAULT_WORKSPACE)
                .param("organizationId", organizationId.value())
                .param("workspaceId", workspaceId.value())
                .update();
    }

    public int insertOrganizationOwner(OrganizationId organizationId, ActorId actorId) {
        return jdbcClient.sql(INSERT_ORGANIZATION_OWNER)
                .param("organizationId", organizationId.value())
                .param("actorId", actorId.value())
                .update();
    }

    public int insertWorkspaceAdmin(
            OrganizationId organizationId,
            WorkspaceId workspaceId,
            ActorId actorId
    ) {
        return jdbcClient.sql(INSERT_WORKSPACE_ADMIN)
                .param("organizationId", organizationId.value())
                .param("workspaceId", workspaceId.value())
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
                        resultSet.getObject("default_workspace_id", UUID.class),
                        resultSet.getString("bootstrap_reference"),
                        resultSet.getString("workspace_slug"),
                        resultSet.getString("workspace_display_name"),
                        resultSet.getString("workspace_status"),
                        resultSet.getObject("owner_actor_id", UUID.class)
                ))
                .optional();
    }

}
