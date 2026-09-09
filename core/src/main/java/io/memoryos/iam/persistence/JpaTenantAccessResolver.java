package io.memoryos.iam.persistence;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.TenantMembership;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Repository
public class JpaTenantAccessResolver implements TenantAccessResolver {

    private final JpaTenantRepository tenants;

    private final IamLockRepository locks;

    public JpaTenantAccessResolver(JpaTenantRepository tenants, IamLockRepository locks) {
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
        this.tenants = Objects.requireNonNull(tenants, "tenants must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TenantMembership> lockActiveMembership(ActorId actorId) {
        var before = findActiveMembership(actorId);
        if (before.isEmpty()) return Optional.empty();
        locks.lockTenantShared(before.orElseThrow().tenantId());
        return findActiveMembership(actorId)
                .filter(after -> after.tenantId().equals(before.orElseThrow().tenantId()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveTenant(TenantId tenantId) {
        return tenants.findActiveTenant(Objects.requireNonNull(tenantId, "tenantId must not be null")).isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantMembership> findActiveMembership(ActorId actorId) {
        List<TenantMembershipEntity> memberships = tenants.findActiveMemberships(
                Objects.requireNonNull(actorId, "actorId must not be null")
        );
        if (memberships.size() > 1) {
            throw new IllegalStateException("actor belongs to more than one active Tenant");
        }
        return memberships.stream()
                .findFirst()
                .map(membership -> new TenantMembership(
                        new TenantId(membership.getTenant().getId()),
                        membership.getTenant().getDisplayName(),
                        membership.getRole()
                ));
    }
}
