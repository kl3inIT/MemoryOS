package io.memoryos.iam.application;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.ExternalIdentityRegistrar;
import io.memoryos.iam.GroupProvisioner;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.TenantMembershipProvisioner;
import io.memoryos.iam.TrustedIdentityAdmission;
import io.memoryos.iam.persistence.IamLockRepository;
import io.memoryos.iam.persistence.JpaTenantRepository;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultTrustedIdentityAdmission implements TrustedIdentityAdmission {

    private final JpaTenantRepository tenants;
    private final IamLockRepository locks;
    private final ExternalIdentityRegistrar identities;
    private final TenantMembershipProvisioner memberships;
    private final GroupProvisioner groups;

    public DefaultTrustedIdentityAdmission(
            JpaTenantRepository tenants,
            IamLockRepository locks,
            ExternalIdentityRegistrar identities,
            TenantMembershipProvisioner memberships,
            GroupProvisioner groups
    ) {
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.memberships = Objects.requireNonNull(memberships, "memberships must not be null");
        this.groups = Objects.requireNonNull(groups, "groups must not be null");
    }

    @Override
    @Transactional
    public ActorId admit(TenantId tenantId, ExternalIdentity identity) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(identity, "identity must not be null");

        // Serialize before resolving even a missing binding: concurrent callbacks must see its committed Actor.
        locks.lockTenant(tenantId);
        if (tenants.findActiveTenant(tenantId).isEmpty()) {
            throw denied();
        }
        ActorId actorId = identities.resolveOrCreateLocked(identity);
        var membership = tenants.findMembership(tenantId, actorId);
        if (membership.isPresent() && membership.get().isActive()) {
            // Replay is not an authority reconciliation: preserve existing roles and Group edges.
            return actorId;
        }
        if (memberships.hasAnyMembership(actorId)) {
            throw denied();
        }
        memberships.grantMember(tenantId, actorId);
        groups.addToBasicGroup(tenantId, actorId);
        return actorId;
    }

    private static IamException denied() {
        return new IamException(IamFailureReason.ACCESS_DENIED, "Identity is not eligible for Tenant admission");
    }
}
