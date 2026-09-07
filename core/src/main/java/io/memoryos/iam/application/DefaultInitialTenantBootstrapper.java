package io.memoryos.iam.application;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.ExternalIdentityRegistrar;
import io.memoryos.iam.ExternalIdentityResolver;
import io.memoryos.iam.GroupProvisioner;
import io.memoryos.iam.InitialTenantBootstrapRequest;
import io.memoryos.iam.InitialTenantBootstrapResult;
import io.memoryos.iam.InitialTenantBootstrapper;
import io.memoryos.iam.TenantBootstrapConflictException;
import io.memoryos.iam.TenantMembershipRole;
import io.memoryos.iam.TenantMembershipStatus;
import io.memoryos.iam.TenantStatus;
import io.memoryos.iam.persistence.ActorEntity;
import io.memoryos.iam.persistence.IamLockRepository;
import io.memoryos.iam.persistence.JpaTenantRepository;
import io.memoryos.iam.persistence.TenantBootstrapStateEntity;
import io.memoryos.iam.persistence.TenantEntity;
import io.memoryos.iam.persistence.TenantMembershipEntity;

@Service
public class DefaultInitialTenantBootstrapper implements InitialTenantBootstrapper {

    private final JpaTenantRepository tenants;
    private final IamLockRepository locks;
    private final ExternalIdentityResolver identityResolver;
    private final ExternalIdentityRegistrar identityRegistrar;
    private final GroupProvisioner groupProvisioner;

    public DefaultInitialTenantBootstrapper(
            JpaTenantRepository tenants,
            IamLockRepository locks,
            ExternalIdentityResolver identityResolver,
            ExternalIdentityRegistrar identityRegistrar,
            GroupProvisioner groupProvisioner
    ) {
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver must not be null");
        this.identityRegistrar = Objects.requireNonNull(identityRegistrar, "identityRegistrar must not be null");
        this.groupProvisioner = Objects.requireNonNull(groupProvisioner, "groupProvisioner must not be null");
    }

    @Override
    @Transactional
    public InitialTenantBootstrapResult bootstrap(InitialTenantBootstrapRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        TenantBootstrapStateEntity state = tenants.lockBootstrapState();
        TenantEntity publishedTenant = state.getTenant();
        if (publishedTenant != null) {
            if (!request.tenantId().value().equals(publishedTenant.getId())) {
                throw conflict("configured tenant ID does not match the published initial tenant");
            }
            InitialTenantBootstrapResult existing = verifyExisting(request, publishedTenant);
            locks.lockTenant(existing.tenantId());
            groupProvisioner.bootstrap(existing.tenantId(), existing.ownerActorId());
            return existing;
        }
        if (tenants.countTenants() != 0) {
            throw conflict("tenant data exists without a published initial tenant");
        }

        ActorId ownerActorId = identityRegistrar.resolveOrCreate(request.ownerIdentity());
        ActorEntity owner = tenants.findActor(ownerActorId)
                .orElseThrow(() -> conflict("configured owner Actor was not persisted"));
        TenantEntity tenant = new TenantEntity(
                request.tenantId().value(),
                request.tenantSlug(),
                request.tenantDisplayName(),
                request.operatorChangeReference()
        );
        tenants.persist(tenant);
        tenants.persist(new TenantMembershipEntity(
                tenant,
                owner,
                TenantMembershipRole.OWNER,
                TenantMembershipStatus.ACTIVE
        ));
        tenants.flush();
        groupProvisioner.bootstrap(request.tenantId(), ownerActorId);
        state.publish(tenant);
        tenants.flush();

        return new InitialTenantBootstrapResult(ownerActorId, request.tenantId(), true);
    }

    private InitialTenantBootstrapResult verifyExisting(
            InitialTenantBootstrapRequest request,
            TenantEntity tenant
    ) {
        if (tenant.getStatus() != TenantStatus.ACTIVE
                || !request.tenantSlug().equals(tenant.getSlug())
                || !request.tenantDisplayName().equals(tenant.getDisplayName())
                || !request.operatorChangeReference().equals(tenant.getBootstrapReference())) {
            throw conflict("bootstrap configuration does not match the published initial tenant");
        }

        TenantMembershipEntity ownerMembership = tenants.findActiveOwner(request.tenantId())
                .orElseThrow(() -> conflict("published initial tenant aggregate is incomplete"));
        ActorId configuredOwner = identityResolver.resolve(request.ownerIdentity())
                .orElseThrow(() -> conflict("configured owner identity is not bound"));
        if (!configuredOwner.value().equals(ownerMembership.getActor().getId())) {
            throw conflict("configured owner identity does not match the published initial owner");
        }

        return new InitialTenantBootstrapResult(configuredOwner, request.tenantId(), false);
    }

    private static TenantBootstrapConflictException conflict(String message) {
        return new TenantBootstrapConflictException(message);
    }
}
