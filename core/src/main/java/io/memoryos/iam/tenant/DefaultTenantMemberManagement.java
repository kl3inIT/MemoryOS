package io.memoryos.iam.tenant;

import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.group.GroupAdministrationGuard;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.iam.tenant.TenantMemberException;
import io.memoryos.iam.tenant.TenantMemberManagement;
import io.memoryos.iam.tenant.TenantMembershipRole;
import io.memoryos.iam.tenant.TenantMembershipStatus;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.memoryos.iam.tenant.persistence.TenantMembershipEntity;

import java.time.Clock;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultTenantMemberManagement implements TenantMemberManagement {

    private final IamAuthorization authorization;
    private final JpaTenantRepository tenants;
    private final GroupAdministrationGuard administrationGuard;
    private final Clock clock;

    @Autowired
    public DefaultTenantMemberManagement(
            IamAuthorization authorization,
            JpaTenantRepository tenants,
            GroupAdministrationGuard administrationGuard
    ) {
        this(authorization, tenants, administrationGuard, Clock.systemUTC());
    }

    DefaultTenantMemberManagement(
            IamAuthorization authorization,
            JpaTenantRepository tenants,
            GroupAdministrationGuard administrationGuard,
            Clock clock
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
        this.administrationGuard = Objects.requireNonNull(
                administrationGuard,
                "administrationGuard must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    @Transactional
    public void activate(ActorId administrator, ActorId target) {
        transition(administrator, target, TenantMembershipStatus.ACTIVE);
    }

    @Override
    @Transactional
    public void deactivate(ActorId administrator, ActorId target) {
        transition(administrator, target, TenantMembershipStatus.INACTIVE);
    }

    private void transition(
            ActorId administrator,
            ActorId target,
            TenantMembershipStatus requestedStatus
    ) {
        Objects.requireNonNull(administrator, "administrator must not be null");
        Objects.requireNonNull(target, "target must not be null");
        TenantId tenantId = authorization.lockAndRequireExclusive(
                administrator,
                IamCapability.USERS_MANAGE
        ).tenantId();
        TenantMembershipEntity member = tenants.findMembershipLocked(tenantId, target)
                .orElseThrow(TenantMemberException::notFound);
        if (member.getRole() == TenantMembershipRole.OWNER) {
            throw TenantMemberException.ownerProtected();
        }
        if (member.getStatus() == requestedStatus) {
            return;
        }
        if (requestedStatus == TenantMembershipStatus.INACTIVE) {
            administrationGuard.requireCanDeactivate(tenantId, target);
        }
        member.changeStatus(requestedStatus, clock.instant());
    }
}
