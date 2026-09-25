package io.memoryos.iam.group;

import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupIdentityPage;
import io.memoryos.iam.GroupQuery;
import io.memoryos.iam.GroupScopeService;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.group.persistence.GroupInvariantRepository;
import io.memoryos.iam.group.persistence.GroupProjectionRepository;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultGroupScopeService implements GroupScopeService {
    private final GroupInvariantRepository invariants;
    private final GroupProjectionRepository projections;

    public DefaultGroupScopeService(
            GroupInvariantRepository invariants,
            GroupProjectionRepository projections
    ) {
        this.invariants = Objects.requireNonNull(invariants, "invariants must not be null");
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public void validateGroupIds(TenantId tenantId, Collection<GroupId> groupIds) {
        TenantId requiredTenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        Set<GroupId> requiredGroupIds = requireGroupIds(groupIds);
        if (!invariants.existingOrdinaryGroups(requiredTenantId, requiredGroupIds).equals(requiredGroupIds)) {
            throw new IamException(
                    IamFailureReason.GROUP_NOT_FOUND,
                    "At least one Group is absent, system-owned, or outside the authorized Tenant"
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void validateManagedGroupIds(
            TenantId tenantId,
            ActorId actorId,
            Collection<GroupId> groupIds
    ) {
        TenantId requiredTenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        ActorId requiredActorId = Objects.requireNonNull(actorId, "actorId must not be null");
        Set<GroupId> requiredGroupIds = requireGroupIds(groupIds);
        if (!invariants.managedGroups(requiredTenantId, requiredActorId, requiredGroupIds)
                .equals(requiredGroupIds)) {
            throw new IamException(
                    IamFailureReason.GROUP_NOT_FOUND,
                    "At least one Group is outside the actor's active ordinary manager scope"
            );
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GroupIdentityPage listManagedGroupOptions(
            TenantId tenantId,
            ActorId actorId,
            @Nullable String search,
            int page,
            int size
    ) {
        return projections.listManagedOptions(
                Objects.requireNonNull(tenantId, "tenantId must not be null"),
                Objects.requireNonNull(actorId, "actorId must not be null"),
                new GroupQuery(search, page, size)
        );
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GroupIdentityPage listGroupOptions(
            TenantId tenantId,
            @Nullable String search,
            int page,
            int size
    ) {
        return projections.listOptions(
                Objects.requireNonNull(tenantId, "tenantId must not be null"),
                new GroupQuery(search, page, size)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isManagedBy(TenantId tenantId, ActorId actorId, GroupId groupId) {
        return invariants.isManagedBy(
                Objects.requireNonNull(tenantId, "tenantId must not be null"),
                Objects.requireNonNull(actorId, "actorId must not be null"),
                Objects.requireNonNull(groupId, "groupId must not be null")
        );
    }

    @Override
    @Transactional(readOnly = true)
    public boolean managesAnyOrdinaryGroup(TenantId tenantId, ActorId actorId) {
        return invariants.managesAnyOrdinaryGroup(
                Objects.requireNonNull(tenantId, "tenantId must not be null"),
                Objects.requireNonNull(actorId, "actorId must not be null")
        );
    }

    private static Set<GroupId> requireGroupIds(Collection<GroupId> groupIds) {
        if (groupIds == null || groupIds.size() > GroupQuery.MAX_SIZE) {
            throw new IamException(
                    IamFailureReason.GROUP_INVALID,
                    "Group IDs are absent or exceed the bounded request size"
            );
        }
        try {
            return Set.copyOf(groupIds);
        } catch (NullPointerException invalidElement) {
            throw new IamException(
                    IamFailureReason.GROUP_INVALID,
                    "Group IDs contain a null value",
                    invalidElement
            );
        }
    }

}
