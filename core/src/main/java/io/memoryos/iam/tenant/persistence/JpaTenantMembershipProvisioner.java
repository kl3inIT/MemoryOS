package io.memoryos.iam.tenant.persistence;

import io.memoryos.shared.ActorId;
import io.memoryos.iam.invitation.InvitationTarget;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.tenant.TenantMembershipProvisioner;
import io.memoryos.iam.tenant.TenantMembershipRole;
import io.memoryos.iam.tenant.TenantMembershipStatus;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import io.memoryos.iam.identity.persistence.ActorEntity;

@Repository
public class JpaTenantMembershipProvisioner implements TenantMembershipProvisioner {

    private final JpaTenantRepository tenants;

    public JpaTenantMembershipProvisioner(JpaTenantRepository tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InvitationTarget> findActiveInvitationTarget(TenantId tenantId) {
        return tenants.findActiveTenant(Objects.requireNonNull(tenantId, "tenantId must not be null"))
                .map(tenant -> new InvitationTarget(new TenantId(tenant.getId()), tenant.getDisplayName()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAnyMembership(ActorId actorId) {
        return tenants.hasAnyMembership(Objects.requireNonNull(actorId, "actorId must not be null"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void grantMember(TenantId tenantId, ActorId actorId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        if (tenants.findMembership(tenantId, actorId).isPresent()) {
            throw new IllegalStateException("actor already has Tenant membership");
        }
        TenantEntity tenant = tenants.findActiveTenant(tenantId)
                .orElseThrow(() -> new IllegalStateException("active Tenant is missing"));
        ActorEntity actor = tenants.findActor(actorId)
                .orElseThrow(() -> new IllegalStateException("Actor is missing"));
        tenants.persist(new TenantMembershipEntity(
                tenant,
                actor,
                TenantMembershipRole.MEMBER,
                TenantMembershipStatus.ACTIVE
        ));
        tenants.flush();
    }
}
