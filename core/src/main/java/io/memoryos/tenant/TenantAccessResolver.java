package io.memoryos.tenant;

import io.memoryos.identity.ActorId;

import java.util.Optional;

public interface TenantAccessResolver {

    boolean hasActiveTenant(ActorId actorId);

    boolean isActiveTenant(TenantId tenantId);

    default Optional<TenantId> findActiveTenant(ActorId actorId) {
        return findSessionAuthority(actorId).map(TenantSessionAuthority::tenantId);
    }

    default Optional<TenantId> findActiveOwnerTenant(ActorId actorId) {
        return findSessionAuthority(actorId)
                .filter(authority -> authority.role() == TenantMembershipRole.OWNER)
                .map(TenantSessionAuthority::tenantId);
    }

    Optional<TenantSessionAuthority> findSessionAuthority(ActorId actorId);
}
