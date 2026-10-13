package io.memoryos.iam.identity;

import io.memoryos.iam.AccountType;
import io.memoryos.iam.ActorProfileRecorder;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.ServiceAccounts;
import io.memoryos.iam.TenantMembershipStatus;
import io.memoryos.iam.group.GroupProvisioner;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.tenant.TenantMembershipProvisioner;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.memoryos.iam.tenant.persistence.TenantMembershipEntity;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class DefaultServiceAccounts implements ServiceAccounts {

    private final JpaTenantRepository tenants;
    private final IamLockRepository locks;
    private final ExternalIdentityRegistrar identities;
    private final ActorProfileRecorder profiles;
    private final TenantMembershipProvisioner memberships;
    private final GroupProvisioner groups;
    private final Clock clock = Clock.systemUTC();

    public DefaultServiceAccounts(
            JpaTenantRepository tenants,
            IamLockRepository locks,
            ExternalIdentityRegistrar identities,
            ActorProfileRecorder profiles,
            TenantMembershipProvisioner memberships,
            GroupProvisioner groups
    ) {
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
        this.memberships = Objects.requireNonNull(memberships, "memberships must not be null");
        this.groups = Objects.requireNonNull(groups, "groups must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ActorId create(TenantId tenantId, UUID owner, String displayName, String uiLanguage) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Assert.hasText(displayName, "displayName must not be blank");
        // As an admission does: membership and its Basic Group edge are written under the Tenant's lock.
        locks.lockTenant(tenantId);
        var identity = new ExternalIdentity(ISSUER, owner.toString());
        ActorId account = identities.createService(identity, uiLanguage);
        profiles.record(account, identity, displayName, null, false);
        memberships.grantMember(tenantId, account);
        groups.addToBasicGroup(tenantId, account);
        return account;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void retire(TenantId tenantId, ActorId account) {
        TenantMembershipEntity membership = tenants.findMembershipLocked(tenantId, account)
                .filter(found -> found.getActor().getAccountType() == AccountType.SERVICE)
                .orElseThrow(() -> new IllegalStateException("service account is missing"));
        if (membership.isActive()) {
            membership.changeStatus(TenantMembershipStatus.INACTIVE, clock.instant());
        }
    }
}
