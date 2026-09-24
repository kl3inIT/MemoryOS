package io.memoryos.iam.group;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;

/**
 * Protects configured-owner and active-administrator survival after the caller has taken the
 * exclusive Tenant authorization lock.
 */
public interface GroupAdministrationGuard {

    void requireCanDeactivate(TenantId tenantId, ActorId actorId);
}
