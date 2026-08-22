package io.memoryos.organization.application;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.ExternalIdentityResolver;
import io.memoryos.organization.InitialOrganizationBootstrapRequest;
import io.memoryos.organization.InitialOrganizationBootstrapResult;
import io.memoryos.organization.InitialOrganizationBootstrapper;
import io.memoryos.organization.OrganizationBootstrapConflictException;
import io.memoryos.organization.OrganizationId;
import io.memoryos.organization.WorkspaceId;
import io.memoryos.organization.persistence.InitialOrganizationRow;
import io.memoryos.organization.persistence.JdbcOrganizationBootstrapRepository;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultInitialOrganizationBootstrapper implements InitialOrganizationBootstrapper {

    private final JdbcOrganizationBootstrapRepository bootstrapRepository;
    private final ExternalIdentityResolver identityResolver;
    private final ExternalIdentityRegistrar identityRegistrar;

    public DefaultInitialOrganizationBootstrapper(
            JdbcOrganizationBootstrapRepository bootstrapRepository,
            ExternalIdentityResolver identityResolver,
            ExternalIdentityRegistrar identityRegistrar
    ) {
        this.bootstrapRepository = Objects.requireNonNull(
                bootstrapRepository,
                "bootstrapRepository must not be null"
        );
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver must not be null");
        this.identityRegistrar = Objects.requireNonNull(identityRegistrar, "identityRegistrar must not be null");
    }

    @Override
    @Transactional
    public InitialOrganizationBootstrapResult bootstrap(InitialOrganizationBootstrapRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        UUID initialOrganizationId = bootstrapRepository.lockInitialOrganizationId().orElse(null);
        if (initialOrganizationId != null) {
            return verifyExisting(request, initialOrganizationId);
        }
        if (bootstrapRepository.countOrganizations() != 0) {
            throw conflict("organization data exists without a published initial organization");
        }

        ActorId ownerActorId = identityRegistrar.resolveOrCreate(request.ownerIdentity());
        OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
        WorkspaceId workspaceId = new WorkspaceId(UUID.randomUUID());

        requireOne(
                bootstrapRepository.insertOrganization(organizationId, request),
                "create initial organization"
        );
        requireOne(
                bootstrapRepository.insertWorkspace(organizationId, workspaceId, request),
                "create default workspace"
        );
        requireOne(
                bootstrapRepository.setDefaultWorkspace(organizationId, workspaceId),
                "set default workspace"
        );
        requireOne(
                bootstrapRepository.insertOrganizationOwner(organizationId, ownerActorId),
                "grant organization owner"
        );
        requireOne(
                bootstrapRepository.insertWorkspaceAdmin(organizationId, workspaceId, ownerActorId),
                "grant workspace admin"
        );
        requireOne(
                bootstrapRepository.publishInitialOrganization(organizationId),
                "publish initial organization"
        );

        return new InitialOrganizationBootstrapResult(ownerActorId, organizationId, workspaceId, true);
    }

    private InitialOrganizationBootstrapResult verifyExisting(
            InitialOrganizationBootstrapRequest request,
            UUID initialOrganizationId
    ) {
        InitialOrganizationRow existing = bootstrapRepository.findInitialOrganization(initialOrganizationId)
                .orElseThrow(() -> conflict("published initial organization aggregate is incomplete"));

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

    private static void requireOne(int updatedRows, String operation) {
        if (updatedRows != 1) {
            throw conflict("failed to " + operation);
        }
    }

    private static OrganizationBootstrapConflictException conflict(String message) {
        return new OrganizationBootstrapConflictException(message);
    }
}
