package io.memoryos.iam.persistence;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.InvitationTarget;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.TenantMembershipProvisioner;
import io.memoryos.iam.TenantMembershipRole;
import io.memoryos.iam.TenantMembershipStatus;

import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
