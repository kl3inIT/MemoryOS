package io.memoryos.iam.application;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.Authority;
import io.memoryos.iam.GroupAction;
import io.memoryos.iam.GroupAdministrationGuard;
import io.memoryos.iam.GroupCapabilityMetadata;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupMemberPage;
import io.memoryos.iam.GroupPage;
import io.memoryos.iam.GroupQuery;
import io.memoryos.iam.GroupService;
import io.memoryos.iam.GroupSummary;
import io.memoryos.iam.GroupSystemKey;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.persistence.GroupEntity;
import io.memoryos.iam.persistence.GroupInvariantRepository;
import io.memoryos.iam.persistence.GroupMembershipEntity;
import io.memoryos.iam.persistence.GroupMembershipRepository;
import io.memoryos.iam.persistence.GroupProjectionRepository;
import io.memoryos.iam.persistence.GroupProjectionRepository.GroupRecord;
import io.memoryos.iam.persistence.GroupProjectionRepository.GroupRecordPage;
import io.memoryos.iam.persistence.GroupRepository;
import io.memoryos.iam.persistence.IamLockRepository;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultGroupService implements GroupService {
    private static final int MAX_GROUP_NAME_LENGTH = 200;
    private static final int MAX_BATCH_SIZE = 100;
    private static final List<GroupCapabilityMetadata> CAPABILITY_REGISTRY = List.of(
            metadata(
                    IamCapability.IAM_ADMIN,
                    "IAM administration",
                    "Full identity, user, group, and Source administration.",
                    false
            ),
            metadata(
                    IamCapability.USERS_MANAGE,
                    "Manage users",
                    "Issue invitations and activate or deactivate Tenant users.",
                    true
            ),
            metadata(
                    IamCapability.GROUPS_READ,
                    "View groups",
                    "View Groups and their memberships.",
                    true
            ),
            metadata(
                    IamCapability.GROUPS_MANAGE,
                    "Manage groups",
                    "Create Groups and manage ordinary Group memberships.",
                    true
            ),
            metadata(
                    IamCapability.SOURCES_READ,
                    "View Sources",
                    "Globally view Source configuration and operation history.",
                    true
            ),
            metadata(
                    IamCapability.SOURCES_MANAGE,
                    "Manage Sources",
                    "Globally create Sources, edit Group associations, upload content, and reindex. "
                            + "Scoped managers can upload or reindex only associated Sources without this grant.",
                    true
            ),
            metadata(
                    IamCapability.SOURCES_DELETE,
                    "Delete Sources",
                    "Globally view and remove Source items or delete Sources, without upload or management access.",
                    true
            )
    );

    private final IamAuthorization authorization;
    private final IamLockRepository locks;
    private final GroupRepository groups;
    private final GroupMembershipRepository memberships;
    private final GroupCapabilityGrantRepository grants;
    private final GroupProjectionRepository projections;
    private final GroupInvariantRepository invariants;
    private final GroupAdministrationGuard administrationGuard;

    public DefaultGroupService(
            IamAuthorization authorization,
            IamLockRepository locks,
            GroupRepository groups,
            GroupMembershipRepository memberships,
            GroupCapabilityGrantRepository grants,
            GroupProjectionRepository projections,
            GroupInvariantRepository invariants,
            GroupAdministrationGuard administrationGuard
    ) {
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
        this.groups = Objects.requireNonNull(groups, "groups must not be null");
        this.memberships = Objects.requireNonNull(memberships, "memberships must not be null");
        this.grants = Objects.requireNonNull(grants, "grants must not be null");
        this.projections = Objects.requireNonNull(projections, "projections must not be null");
        this.invariants = Objects.requireNonNull(invariants, "invariants must not be null");
        this.administrationGuard = Objects.requireNonNull(
                administrationGuard,
                "administrationGuard must not be null"
        );
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GroupPage list(ActorId actorId, GroupQuery query) {
        ActorId requiredActorId = requireActor(actorId);
        GroupQuery requiredQuery = Objects.requireNonNull(query, "query must not be null");
        IamAccess access = authorization.require(requiredActorId, IamCapability.GROUPS_READ, true);
        Set<IamCapability> effectiveCapabilities = authorization.effectiveCapabilities(requiredActorId);
        GroupRecordPage page = projections.list(
                access.tenantId(),
                requiredActorId,
                access.authority() == Authority.GLOBAL,
                requiredQuery
        );
        return new GroupPage(
                page.items().stream()
                        .map(group -> summary(group, effectiveCapabilities))
                        .toList(),
                page.page(),
                page.size(),
                page.totalItems(),
                page.totalPages()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroupCapabilityMetadata> capabilities(ActorId actorId) {
        authorization.require(requireActor(actorId), IamCapability.GROUPS_READ, true);
        return CAPABILITY_REGISTRY;
    }

    @Override
    @Transactional
    public GroupSummary create(ActorId actorId, String name) {
        ActorId requiredActorId = requireActor(actorId);
        String requiredName = requireName(name);
        IamAccess access = authorization.lockAndRequireExclusive(
                requiredActorId,
                IamCapability.GROUPS_MANAGE
        );
        requireUniqueName(access.tenantId(), requiredName, null);

        GroupEntity group = new GroupEntity(
                groups.tenantReference(access.tenantId()),
                UUID.randomUUID(),
                requiredName,
                null
        );
        try {
            groups.persist(group);
            groups.flush();
        } catch (DataIntegrityViolationException conflict) {
            throw groupConflict(requiredName, conflict);
        }
        return summaryAfterMutation(access.tenantId(), requiredActorId, new GroupId(group.getId()));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GroupSummary get(ActorId actorId, GroupId groupId) {
        ActorId requiredActorId = requireActor(actorId);
        GroupId requiredGroupId = requireGroup(groupId);
        IamAccess access = authorization.require(requiredActorId, IamCapability.GROUPS_READ, true);
        GroupRecord group = projections.detail(
                access.tenantId(),
                requiredActorId,
                requiredGroupId,
                access.authority() == Authority.GLOBAL
        ).orElseThrow(() -> groupNotFound(requiredGroupId));
        return summary(group, authorization.effectiveCapabilities(requiredActorId));
    }

    @Override
    @Transactional
    public GroupSummary rename(ActorId actorId, GroupId groupId, String name) {
        ActorId requiredActorId = requireActor(actorId);
        GroupId requiredGroupId = requireGroup(groupId);
        String requiredName = requireName(name);
        IamAccess access = authorization.lockAndRequireExclusive(
                requiredActorId,
                IamCapability.GROUPS_MANAGE
        );
        GroupEntity group = ordinaryGroup(access.tenantId(), requiredGroupId);
        requireUniqueName(access.tenantId(), requiredName, requiredGroupId);
        group.rename(requiredName);
        try {
            groups.flush();
        } catch (DataIntegrityViolationException conflict) {
            throw groupConflict(requiredName, conflict);
        }
        return summaryAfterMutation(access.tenantId(), requiredActorId, requiredGroupId);
    }

    @Override
    @Transactional
    public void delete(ActorId actorId, GroupId groupId) {
        ActorId requiredActorId = requireActor(actorId);
        GroupId requiredGroupId = requireGroup(groupId);
        IamAccess access = authorization.lockAndRequireExclusive(
                requiredActorId,
                IamCapability.GROUPS_MANAGE
        );
        GroupEntity group = ordinaryGroup(access.tenantId(), requiredGroupId);
        groups.remove(group);
        groups.flush();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GroupMemberPage members(ActorId actorId, GroupId groupId, GroupQuery query) {
        AccessToGroup access = visibleGroup(actorId, groupId, IamCapability.GROUPS_READ);
        return projections.members(
                access.access().tenantId(),
                access.group().id(),
                Objects.requireNonNull(query, "query must not be null")
        );
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GroupMemberPage candidates(ActorId actorId, GroupId groupId, GroupQuery query) {
        AccessToGroup access = visibleGroup(actorId, groupId, IamCapability.GROUPS_MANAGE);
        if (access.group().systemKey() != null
                && !authorization.effectiveCapabilities(requireActor(actorId))
                        .contains(IamCapability.IAM_ADMIN)) {
            throw new IamException(
                    IamFailureReason.GROUP_PROTECTED,
                    "System Group membership candidates require IAM_ADMIN"
            );
        }
        return projections.candidates(
                access.access().tenantId(),
                access.group().id(),
                Objects.requireNonNull(query, "query must not be null")
        );
    }

    @Override
    @Transactional
    public void addMembers(ActorId actorId, GroupId groupId, Collection<ActorId> actorIds) {
        ActorId requiredActorId = requireActor(actorId);
        GroupId requiredGroupId = requireGroup(groupId);
        Set<ActorId> requiredMembers = requireActorIds(actorIds);
        IamAccess access = lockGroupMembershipMutation(requiredActorId);
        GroupEntity group = mutableMembershipGroup(
                requiredActorId,
                access,
                requiredGroupId
        );
        if (access.authority() == Authority.SCOPED) {
            requireDelegable(requiredActorId, group);
        }
        if (!invariants.existingTenantMembers(access.tenantId(), requiredMembers).equals(requiredMembers)) {
            throw new IamException(
                    IamFailureReason.GROUP_MEMBER_NOT_FOUND,
                    "At least one target actor is not a member of the authorized Tenant"
            );
        }
        Set<ActorId> existingMembers = invariants.existingGroupMembers(
                access.tenantId(),
                requiredGroupId,
                requiredMembers
        );
        for (ActorId memberActorId : requiredMembers) {
            if (!existingMembers.contains(memberActorId)) {
                memberships.add(
                        group,
                        memberships.tenantMembershipReference(access.tenantId(), memberActorId),
                        false
                );
            }
        }
        memberships.flush();
    }

    @Override
    @Transactional
    public void removeMember(ActorId actorId, GroupId groupId, ActorId memberActorId) {
        ActorId requiredActorId = requireActor(actorId);
        GroupId requiredGroupId = requireGroup(groupId);
        ActorId requiredMemberActorId = requireActor(memberActorId);
        IamAccess access = lockGroupMembershipMutation(requiredActorId);
        GroupEntity group = mutableMembershipGroup(requiredActorId, access, requiredGroupId);
        GroupMembershipEntity membership = memberships.find(
                access.tenantId(),
                requiredGroupId,
                requiredMemberActorId
        ).orElseThrow(() -> memberNotFound(requiredGroupId, requiredMemberActorId));
        if (access.authority() == Authority.SCOPED && membership.isManager()) {
            throw new IamException(
                    IamFailureReason.ACCESS_DENIED,
                    "Scoped managers cannot remove Group manager memberships"
            );
        }
        if (group.getSystemKey() == GroupSystemKey.ADMIN) {
            administrationGuard.requireCanDeactivate(access.tenantId(), requiredMemberActorId);
        }
        memberships.remove(membership);
        memberships.flush();
    }

    @Override
    @Transactional
    public void assignManager(ActorId actorId, GroupId groupId, ActorId memberActorId) {
        setManager(actorId, groupId, memberActorId, true);
    }

    @Override
    @Transactional
    public void removeManager(ActorId actorId, GroupId groupId, ActorId memberActorId) {
        setManager(actorId, groupId, memberActorId, false);
    }

    @Override
    @Transactional
    public void replaceCapabilities(
            ActorId actorId,
            GroupId groupId,
            Collection<IamCapability> capabilities
    ) {
        ActorId requiredActorId = requireActor(actorId);
        GroupId requiredGroupId = requireGroup(groupId);
        Set<IamCapability> requiredCapabilities = requireCapabilities(capabilities);
        if (requiredCapabilities.contains(IamCapability.IAM_ADMIN)) {
            throw new IamException(
                    IamFailureReason.GROUP_PROTECTED,
                    "IAM_ADMIN is reserved to the Admin Group"
            );
        }
        IamAccess access = authorization.lockAndRequireAdministration(requiredActorId);
        GroupEntity group = ordinaryGroup(access.tenantId(), requiredGroupId);
        grants.replace(group, requiredCapabilities);
        groups.flush();
    }

    @Override
    @Transactional
    public void replaceOrdinaryMemberships(
            ActorId actorId,
            ActorId memberActorId,
            Collection<GroupId> groupIds
    ) {
        ActorId requiredActorId = requireActor(actorId);
        ActorId requiredMemberActorId = requireActor(memberActorId);
        Set<GroupId> requiredGroupIds = requireGroupIds(groupIds);
        IamAccess access = authorization.lockAndRequireAdministration(requiredActorId);
        if (!invariants.existingTenantMembers(
                access.tenantId(),
                Set.of(requiredMemberActorId)
        ).contains(requiredMemberActorId)) {
            throw memberNotFound(null, requiredMemberActorId);
        }
        if (!invariants.existingOrdinaryGroups(
                access.tenantId(),
                requiredGroupIds
        ).equals(requiredGroupIds)) {
            throw new IamException(
                    IamFailureReason.GROUP_NOT_FOUND,
                    "At least one replacement Group is absent, system-owned, or outside the Tenant"
            );
        }

        memberships.removeOrdinaryMembershipsExcept(
                access.tenantId(),
                requiredMemberActorId,
                requiredGroupIds
        );
        Set<GroupId> existingMemberships = invariants.existingGroupMemberships(
                access.tenantId(),
                requiredMemberActorId,
                requiredGroupIds
        );
        for (GroupId retainedGroupId : requiredGroupIds) {
            if (!existingMemberships.contains(retainedGroupId)) {
                GroupEntity group = groups.tenantGroupReference(access.tenantId(), retainedGroupId);
                memberships.add(
                        group,
                        memberships.tenantMembershipReference(
                                access.tenantId(),
                                requiredMemberActorId
                        ),
                        false
                );
            }
        }
        memberships.flush();
    }

    private void setManager(
            ActorId actorId,
            GroupId groupId,
            ActorId memberActorId,
            boolean manager
    ) {
        ActorId requiredActorId = requireActor(actorId);
        GroupId requiredGroupId = requireGroup(groupId);
        ActorId requiredMemberActorId = requireActor(memberActorId);
        IamAccess access = authorization.lockAndRequireExclusive(
                requiredActorId,
                IamCapability.GROUPS_MANAGE
        );
        ordinaryGroup(access.tenantId(), requiredGroupId);
        GroupMembershipEntity membership = memberships.find(
                access.tenantId(),
                requiredGroupId,
                requiredMemberActorId
        ).orElseThrow(() -> memberNotFound(requiredGroupId, requiredMemberActorId));
        membership.setManager(manager);
        memberships.flush();
    }

    private AccessToGroup visibleGroup(
            ActorId actorId,
            GroupId groupId,
            IamCapability requiredCapability
    ) {
        ActorId requiredActorId = requireActor(actorId);
        GroupId requiredGroupId = requireGroup(groupId);
        IamAccess access = authorization.require(requiredActorId, requiredCapability, true);
        GroupRecord group = projections.detail(
                access.tenantId(),
                requiredActorId,
                requiredGroupId,
                access.authority() == Authority.GLOBAL
        ).orElseThrow(() -> groupNotFound(requiredGroupId));
        return new AccessToGroup(access, group);
    }

    private IamAccess lockGroupMembershipMutation(ActorId actorId) {
        IamAccess beforeLock = authorization.require(actorId, IamCapability.GROUPS_MANAGE, true);
        locks.lockTenant(beforeLock.tenantId());
        IamAccess afterLock = authorization.require(actorId, IamCapability.GROUPS_MANAGE, true);
        if (!beforeLock.tenantId().equals(afterLock.tenantId())) {
            throw new IamException(
                    IamFailureReason.ACCESS_DENIED,
                    "Actor authority changed Tenant while acquiring the exclusive authority lock"
            );
        }
        return afterLock;
    }

    private GroupEntity mutableMembershipGroup(
            ActorId actorId,
            IamAccess access,
            GroupId groupId
    ) {
        GroupEntity group = groups.find(access.tenantId(), groupId)
                .orElseThrow(() -> groupNotFound(groupId));
        if (group.isSystemGroup()) {
            if (!authorization.effectiveCapabilities(actorId).contains(IamCapability.IAM_ADMIN)) {
                throw new IamException(
                        IamFailureReason.GROUP_PROTECTED,
                        "System Group memberships require IAM_ADMIN"
                );
            }
            return group;
        }
        if (access.authority() == Authority.SCOPED
                && !invariants.isManagedBy(access.tenantId(), actorId, groupId)) {
            throw groupNotFound(groupId);
        }
        return group;
    }

    private void requireDelegable(ActorId actorId, GroupEntity group) {
        Set<IamCapability> groupCapabilities = IamCapability.expand(grants.findCapabilities(group));
        Set<IamCapability> managerCapabilities = authorization.effectiveCapabilities(actorId);
        if (!managerCapabilities.containsAll(groupCapabilities)) {
            throw new IamException(
                    IamFailureReason.MANAGER_AMPLIFICATION_DENIED,
                    "Scoped manager lacks at least one expanded capability granted by the target Group"
            );
        }
    }

    private GroupEntity ordinaryGroup(TenantId tenantId, GroupId groupId) {
        GroupEntity group = groups.find(tenantId, groupId)
                .orElseThrow(() -> groupNotFound(groupId));
        if (group.isSystemGroup()) {
            throw new IamException(
                    IamFailureReason.GROUP_PROTECTED,
                    "System Groups cannot be renamed, deleted, assigned managers, or have grants edited"
            );
        }
        return group;
    }

    private GroupSummary summaryAfterMutation(TenantId tenantId, ActorId actorId, GroupId groupId) {
        GroupRecord group = projections.detail(tenantId, actorId, groupId, true)
                .orElseThrow(() -> groupNotFound(groupId));
        return summary(group, authorization.effectiveCapabilities(actorId));
    }

    private static GroupSummary summary(
            GroupRecord group,
            Set<IamCapability> effectiveCapabilities
    ) {
        EnumSet<GroupAction> actions = EnumSet.noneOf(GroupAction.class);
        boolean iamAdmin = effectiveCapabilities.contains(IamCapability.IAM_ADMIN);
        boolean managesGroupsGlobally = effectiveCapabilities.contains(IamCapability.GROUPS_MANAGE);
        if (group.systemKey() == null) {
            if (managesGroupsGlobally) {
                actions.add(GroupAction.RENAME);
                actions.add(GroupAction.DELETE);
                actions.add(GroupAction.MANAGE_MANAGERS);
            }
            if (managesGroupsGlobally || group.managedByActor()) {
                actions.add(GroupAction.MANAGE_MEMBERS);
            }
            if (iamAdmin) {
                actions.add(GroupAction.MANAGE_GRANTS);
            }
        } else if (iamAdmin) {
            actions.add(GroupAction.MANAGE_MEMBERS);
        }
        if (effectiveCapabilities.contains(IamCapability.SOURCES_MANAGE)) {
            actions.add(GroupAction.MANAGE_SOURCES);
        }
        return new GroupSummary(
                group.id(),
                group.name(),
                group.systemKey(),
                group.memberCount(),
                group.managerCount(),
                group.capabilities(),
                actions
        );
    }

    private void requireUniqueName(
            TenantId tenantId,
            String name,
            @Nullable GroupId excludedGroupId
    ) {
        if (groups.nameExists(tenantId, name, excludedGroupId)) {
            throw groupConflict(name, null);
        }
    }

    private static String requireName(String name) {
        if (name == null) {
            throw invalid("Group name is null");
        }
        String normalized = name.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_GROUP_NAME_LENGTH) {
            throw invalid("Group name must contain between 1 and 200 characters");
        }
        return normalized;
    }

    private static Set<ActorId> requireActorIds(Collection<ActorId> actorIds) {
        if (actorIds == null || actorIds.isEmpty() || actorIds.size() > MAX_BATCH_SIZE) {
            throw invalid("Actor IDs are absent, empty, or exceed the bounded request size");
        }
        try {
            return Set.copyOf(actorIds);
        } catch (NullPointerException invalidElement) {
            throw invalid("Actor IDs contain a null value", invalidElement);
        }
    }

    private static Set<GroupId> requireGroupIds(Collection<GroupId> groupIds) {
        if (groupIds == null || groupIds.size() > MAX_BATCH_SIZE) {
            throw invalid("Group IDs are absent or exceed the bounded request size");
        }
        try {
            return Set.copyOf(groupIds);
        } catch (NullPointerException invalidElement) {
            throw invalid("Group IDs contain a null value", invalidElement);
        }
    }

    private static Set<IamCapability> requireCapabilities(
            Collection<IamCapability> capabilities
    ) {
        if (capabilities == null || capabilities.size() > IamCapability.values().length) {
            throw invalid("Capabilities are absent or exceed the registry size");
        }
        try {
            return capabilities.isEmpty()
                    ? Set.of()
                    : Set.copyOf(EnumSet.copyOf(capabilities));
        } catch (NullPointerException invalidElement) {
            throw invalid("Capabilities contain a null value", invalidElement);
        }
    }

    private static ActorId requireActor(ActorId actorId) {
        return Objects.requireNonNull(actorId, "actorId must not be null");
    }

    private static GroupId requireGroup(GroupId groupId) {
        return Objects.requireNonNull(groupId, "groupId must not be null");
    }

    private static GroupCapabilityMetadata metadata(
            IamCapability capability,
            String label,
            String description,
            boolean editable
    ) {
        return new GroupCapabilityMetadata(
                capability,
                label,
                description,
                editable,
                capability.impliedCapabilities()
        );
    }

    private static IamException groupNotFound(GroupId groupId) {
        return new IamException(
                IamFailureReason.GROUP_NOT_FOUND,
                "Group is absent from authorized scope: " + groupId
        );
    }

    private static IamException memberNotFound(GroupId groupId, ActorId actorId) {
        return new IamException(
                IamFailureReason.GROUP_MEMBER_NOT_FOUND,
                "Group member is absent: group=" + groupId + ", actor=" + actorId
        );
    }

    private static IamException groupConflict(String name, Throwable cause) {
        String diagnostic = "Group name conflicts case-insensitively within the Tenant: " + name;
        return cause == null
                ? new IamException(IamFailureReason.GROUP_CONFLICT, diagnostic)
                : new IamException(IamFailureReason.GROUP_CONFLICT, diagnostic, cause);
    }

    private static IamException invalid(String diagnosticMessage) {
        return new IamException(IamFailureReason.GROUP_INVALID, diagnosticMessage);
    }

    private static IamException invalid(String diagnosticMessage, Throwable cause) {
        return new IamException(IamFailureReason.GROUP_INVALID, diagnosticMessage, cause);
    }

    private record AccessToGroup(IamAccess access, GroupRecord group) {
    }
}
