package io.memoryos.iam;


import java.util.Optional;

public interface TenantAccessResolver {

    /** Requires a write transaction; serializes membership validation with IAM revocation. */
    Optional<TenantMembership> lockActiveMembership(ActorId actorId);

    boolean isActiveTenant(TenantId tenantId);

    Optional<TenantMembership> findActiveMembership(ActorId actorId);

    default boolean hasActiveTenant(ActorId actorId) {
        return findActiveMembership(actorId).isPresent();
    }

    default Optional<TenantId> findActiveTenant(ActorId actorId) {
        return findActiveMembership(actorId).map(TenantMembership::tenantId);
    }
}
