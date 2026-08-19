package io.memoryos.organization.persistence;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.ExternalIdentityResolver;
import io.memoryos.organization.InitialOrganizationBootstrapRequest;
import io.memoryos.organization.InitialOrganizationBootstrapResult;
import io.memoryos.organization.InitialOrganizationBootstrapper;
import io.memoryos.organization.OrganizationBootstrapConflictException;
import io.memoryos.organization.OrganizationId;
import io.memoryos.organization.WorkspaceId;

import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcInitialOrganizationBootstrapper implements InitialOrganizationBootstrapper {

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

    private static final String SELECT_EXISTING_AGGREGATE = """
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

    private static final String COUNT_WORKSPACES = """
            SELECT COUNT(*) FROM workspaces WHERE organization_id = :organizationId
            """;

    private static final String COUNT_ORGANIZATION_MEMBERSHIPS = """
            SELECT COUNT(*) FROM organization_memberships WHERE organization_id = :organizationId
            """;

    private static final String COUNT_WORKSPACE_MEMBERSHIPS = """
            SELECT COUNT(*) FROM workspace_memberships WHERE organization_id = :organizationId
            """;

    private final JdbcClient jdbcClient;
    private final ExternalIdentityResolver identityResolver;
    private final ExternalIdentityRegistrar identityRegistrar;

    public JdbcInitialOrganizationBootstrapper(
            JdbcClient jdbcClient,
            ExternalIdentityResolver identityResolver,
            ExternalIdentityRegistrar identityRegistrar
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver must not be null");
        this.identityRegistrar = Objects.requireNonNull(identityRegistrar, "identityRegistrar must not be null");
    }

    @Override
    @Transactional
    public InitialOrganizationBootstrapResult bootstrap(InitialOrganizationBootstrapRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        UUID initialOrganizationId = jdbcClient.sql(LOCK_BOOTSTRAP_STATE)
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (initialOrganizationId != null) {
            return verifyExisting(request, initialOrganizationId);
        }

        long organizationCount = jdbcClient.sql(COUNT_ORGANIZATIONS).query(Long.class).single();
        if (organizationCount != 0) {
            throw conflict("organization data exists without a published initial organization");
        }

        ActorId ownerActorId = identityRegistrar.resolveOrCreate(request.ownerIdentity());
        OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());

        requireOne(jdbcClient.sql(INSERT_ORGANIZATION)
                .param("id", organizationId.value())
                .param("slug", request.organizationSlug())
                .param("displayName", request.organizationDisplayName())
                .param("bootstrapReference", request.operatorChangeReference())
                .update(), "create initial organization");
        requireOne(jdbcClient.sql(INSERT_WORKSPACE)
                .param("id", workspaceId.value())
                .param("organizationId", organizationId.value())
                .param("slug", request.defaultWorkspaceSlug())
                .param("displayName", request.defaultWorkspaceDisplayName())
                .update(), "create default workspace");
        requireOne(jdbcClient.sql(SET_DEFAULT_WORKSPACE)
                .param("organizationId", organizationId.value())
                .param("workspaceId", workspaceId.value())
                .update(), "set default workspace");
        requireOne(jdbcClient.sql(INSERT_ORGANIZATION_OWNER)
                .param("organizationId", organizationId.value())
                .param("actorId", ownerActorId.value())
                .update(), "grant organization owner");
        requireOne(jdbcClient.sql(INSERT_WORKSPACE_ADMIN)
                .param("organizationId", organizationId.value())
                .param("workspaceId", workspaceId.value())
                .param("actorId", ownerActorId.value())
                .update(), "grant workspace admin");
        requireOne(jdbcClient.sql(PUBLISH_INITIAL_ORGANIZATION)
                .param("organizationId", organizationId.value())
                .update(), "publish initial organization");

        return new InitialOrganizationBootstrapResult(ownerActorId, organizationId, workspaceId, true);
    }

    private InitialOrganizationBootstrapResult verifyExisting(
            InitialOrganizationBootstrapRequest request,
            UUID initialOrganizationId
    ) {
        ExistingOrganization existing = jdbcClient.sql(SELECT_EXISTING_AGGREGATE)
                .param("organizationId", initialOrganizationId)
                .query((resultSet, ignored) -> new ExistingOrganization(
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
                .optional()
                .orElseThrow(() -> conflict("published initial organization aggregate is incomplete"));

        if (count(COUNT_ORGANIZATIONS, null) != 1
                || count(COUNT_WORKSPACES, initialOrganizationId) != 1
                || count(COUNT_ORGANIZATION_MEMBERSHIPS, initialOrganizationId) != 1
                || count(COUNT_WORKSPACE_MEMBERSHIPS, initialOrganizationId) != 1) {
            throw conflict("published initial organization aggregate contains unexpected rows");
        }
        if (!"ACTIVE".equals(existing.organizationStatus())
                || !"ACTIVE".equals(existing.workspaceStatus())
                || !request.organizationSlug().equals(existing.organizationSlug())
                || !request.organizationDisplayName().equals(existing.organizationDisplayName())
                || !request.defaultWorkspaceSlug().equals(existing.workspaceSlug())
                || !request.defaultWorkspaceDisplayName().equals(existing.workspaceDisplayName())
                || !request.operatorChangeReference().equals(existing.bootstrapReference())) {
            throw conflict("bootstrap configuration does not match the published initial organization");
        }

        ActorId configuredOwner = identityResolver.resolve(request.ownerIdentity())
                .orElseThrow(() -> conflict("configured owner identity is not bound"));
        if (!configuredOwner.value().equals(existing.ownerActorId())) {
            throw conflict("configured owner identity does not match the published initial owner");
        }

        return new InitialOrganizationBootstrapResult(
                configuredOwner,
                new OrganizationId(existing.organizationId()),
                new WorkspaceId(existing.defaultWorkspaceId()),
                false
        );
    }

    private long count(String sql, UUID organizationId) {
        JdbcClient.StatementSpec statement = jdbcClient.sql(sql);
        if (organizationId != null) {
            statement = statement.param("organizationId", organizationId);
        }
        return statement.query(Long.class).single();
    }

    private static void requireOne(int updatedRows, String operation) {
        if (updatedRows != 1) {
            throw conflict("failed to " + operation);
        }
    }

    private static OrganizationBootstrapConflictException conflict(String message) {
        return new OrganizationBootstrapConflictException(message);
    }

    private record ExistingOrganization(
            UUID organizationId,
            String organizationSlug,
            String organizationDisplayName,
            String organizationStatus,
            UUID defaultWorkspaceId,
            String bootstrapReference,
            String workspaceSlug,
            String workspaceDisplayName,
            String workspaceStatus,
            UUID ownerActorId
    ) {
    }
}