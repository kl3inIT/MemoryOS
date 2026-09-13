package io.memoryos.iam;

import java.util.Collection;

import org.jspecify.annotations.Nullable;

/**
 * Bounded Tenant-qualified ordinary Group projections for already-authorized Source association callers.
 * A caller that mutates associations must hold the exclusive IAM guard through its write.
 */
public interface GroupScopeService {

    void validateGroupIds(TenantId tenantId, Collection<GroupId> groupIds);

    void validateManagedGroupIds(TenantId tenantId, ActorId actorId, Collection<GroupId> groupIds);

    GroupIdentityPage listManagedGroupOptions(
            TenantId tenantId,
            ActorId actorId,
            @Nullable String search,
            int page,
            int size
    );

    GroupIdentityPage listGroupOptions(
            TenantId tenantId,
            @Nullable String search,
            int page,
            int size
    );

    boolean isManagedBy(TenantId tenantId, ActorId actorId, GroupId groupId);
}
