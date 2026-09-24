package io.memoryos.iam.tenant;


import java.util.Optional;
import io.memoryos.iam.identity.ActorId;

public interface TenantAccessResolver {

    /** Requires a write transaction; serializes membership validation with IAM revocation. */
    Optional<TenantMembership> lockActiveMembership(ActorId actorId);

    boolean isActiveTenant(TenantId tenantId);

    /**
     * The Tenant the deployment was bootstrapped with, once it is published. Deployment-wide settings, such as the
     * search configuration every Tenant shares, belong to it.
     */
    Optional<TenantId> operatingTenant();

    Optional<TenantMembership> findActiveMembership(ActorId actorId);

    default boolean hasActiveTenant(ActorId actorId) {
        return findActiveMembership(actorId).isPresent();
    }

    default Optional<TenantId> findActiveTenant(ActorId actorId) {
        return findActiveMembership(actorId).map(TenantMembership::tenantId);
    }
}
