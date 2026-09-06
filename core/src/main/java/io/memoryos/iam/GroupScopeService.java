package io.memoryos.iam;

import java.util.Collection;

import org.jspecify.annotations.Nullable;

/**
 * Bounded Tenant-qualified Group projections for an already-authorized cross-capability caller.
 * A caller that mutates associations must hold the exclusive IAM guard through its write.
 */
public interface GroupScopeService {

    void validateGroupIds(TenantId tenantId, Collection<GroupId> groupIds);

    GroupIdentityPage listGroupOptions(
            TenantId tenantId,
            @Nullable String search,
            int page,
            int size
    );

    boolean isManagedBy(TenantId tenantId, ActorId actorId, GroupId groupId);
}
