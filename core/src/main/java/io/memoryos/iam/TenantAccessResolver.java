package io.memoryos.iam;


import java.util.Optional;

public interface TenantAccessResolver {

    boolean isActiveTenant(TenantId tenantId);

    Optional<TenantMembership> findActiveMembership(ActorId actorId);

    default boolean hasActiveTenant(ActorId actorId) {
        return findActiveMembership(actorId).isPresent();
    }

    default Optional<TenantId> findActiveTenant(ActorId actorId) {
        return findActiveMembership(actorId).map(TenantMembership::tenantId);
    }
}
