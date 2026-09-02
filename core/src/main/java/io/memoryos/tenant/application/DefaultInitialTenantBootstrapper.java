package io.memoryos.tenant.application;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentityRegistrar;
import io.memoryos.identity.ExternalIdentityResolver;
import io.memoryos.tenant.InitialTenantBootstrapRequest;
import io.memoryos.tenant.InitialTenantBootstrapResult;
import io.memoryos.tenant.InitialTenantBootstrapper;
import io.memoryos.tenant.TenantBootstrapConflictException;
import io.memoryos.tenant.TenantId;
import io.memoryos.tenant.persistence.InitialTenantRow;
import io.memoryos.tenant.persistence.JdbcTenantBootstrapRepository;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultInitialTenantBootstrapper implements InitialTenantBootstrapper {

    private final JdbcTenantBootstrapRepository bootstrapRepository;
    private final ExternalIdentityResolver identityResolver;
    private final ExternalIdentityRegistrar identityRegistrar;

    public DefaultInitialTenantBootstrapper(
            JdbcTenantBootstrapRepository bootstrapRepository,
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
    public InitialTenantBootstrapResult bootstrap(InitialTenantBootstrapRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        TenantId tenantId = request.tenantId();
        UUID initialTenantId = bootstrapRepository.lockInitialTenantId().orElse(null);
        if (initialTenantId != null) {
            if (!tenantId.value().equals(initialTenantId)) {
                throw conflict("configured tenant ID does not match the published initial tenant");
            }
            return verifyExisting(request, initialTenantId);
        }
        if (bootstrapRepository.countTenants() != 0) {
            throw conflict("tenant data exists without a published initial tenant");
        }

        ActorId ownerActorId = identityRegistrar.resolveOrCreate(request.ownerIdentity());

        requireOne(bootstrapRepository.insertTenant(request), "create initial tenant");
        requireOne(
                bootstrapRepository.insertTenantOwner(tenantId, ownerActorId),
                "grant tenant owner"
        );
        requireOne(
                bootstrapRepository.publishInitialTenant(tenantId),
                "publish initial tenant"
        );

        return new InitialTenantBootstrapResult(ownerActorId, tenantId, true);
    }

    private InitialTenantBootstrapResult verifyExisting(
            InitialTenantBootstrapRequest request,
            UUID initialTenantId
    ) {
        InitialTenantRow existing = bootstrapRepository.findInitialTenant(initialTenantId)
                .orElseThrow(() -> conflict("published initial tenant aggregate is incomplete"));

        if (!"ACTIVE".equals(existing.tenantStatus())
                || !request.tenantSlug().equals(existing.tenantSlug())
                || !request.tenantDisplayName().equals(existing.tenantDisplayName())
                || !request.operatorChangeReference().equals(existing.bootstrapReference())) {
            throw conflict("bootstrap configuration does not match the published initial tenant");
        }

        ActorId configuredOwner = identityResolver.resolve(request.ownerIdentity())
                .orElseThrow(() -> conflict("configured owner identity is not bound"));
        if (!configuredOwner.value().equals(existing.ownerActorId())) {
            throw conflict("configured owner identity does not match the published initial owner");
        }

        return new InitialTenantBootstrapResult(
                configuredOwner,
                new TenantId(existing.tenantId()),
                false
        );
    }

    private static void requireOne(int updatedRows, String operation) {
        if (updatedRows != 1) {
            throw conflict("failed to " + operation);
        }
    }

    private static TenantBootstrapConflictException conflict(String message) {
        return new TenantBootstrapConflictException(message);
    }
}
