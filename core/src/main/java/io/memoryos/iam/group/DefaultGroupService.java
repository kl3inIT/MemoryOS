package io.memoryos.iam.group;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditOutcome;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.iam.Authority;
import io.memoryos.iam.GroupCapabilityMetadata;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupMemberPage;
import io.memoryos.iam.GroupPage;
import io.memoryos.iam.GroupPermissions;
import io.memoryos.iam.GroupQuery;
import io.memoryos.iam.GroupService;
import io.memoryos.iam.GroupSummary;
import io.memoryos.iam.GroupSystemKey;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.group.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.group.persistence.GroupEntity;
import io.memoryos.iam.group.persistence.GroupInvariantRepository;
import io.memoryos.iam.group.persistence.GroupMembershipEntity;
import io.memoryos.iam.group.persistence.GroupMembershipRepository;
import io.memoryos.iam.group.persistence.GroupProjectionRepository;
import io.memoryos.iam.group.persistence.GroupProjectionRepository.GroupRecord;
import io.memoryos.iam.group.persistence.GroupProjectionRepository.GroupRecordPage;
import io.memoryos.iam.group.persistence.GroupRepository;

import java.util.ArrayList;
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
            metadata(IamCapability.MODELS_MANAGE, "Manage models",
                    "Configure Chat providers, credentials, models and access within the Tenant.", true),
            metadata(IamCapability.MCP_MANAGE, "Manage MCP servers",
                    "Register remote MCP servers, their authentication and OAuth clients, enable their tools "
                            + "and control Group access within the Tenant.", true),
            metadata(IamCapability.AGENTS_CREATE, "Create agents",
                    "Create custom Chat agents and share the agents they own or edit.", true),
            metadata(IamCapability.CHAT_HISTORY_READ, "View conversation history",
                    "Read and export the questions and answers of everyone in the Tenant. Every transcript read is "
                            + "itself recorded in the audit log.", true),
            metadata(IamCapability.AUDIT_READ, "View audit log",
                    "Read and export who changed sign-in, users, Groups, models, connections and Sources within the Tenant.", true),
            metadata(IamCapability.AGENTS_MANAGE, "Manage agents",
                    "Edit, share, publish, feature, restore and transfer every custom agent, edit the default agent, "
                            + "and administer agent labels and public prompt shortcuts within the Tenant.", true),
            metadata(
                    IamCapability.SYSTEM_ADMIN,
                    "Administrator access",
                    "All product capabilities, including search, identity, user, group, Source, and model administration.",
                    false
            ),
            metadata(
                    IamCapability.SYSTEM_BASIC,
                    "Basic access",
                    "Search and read eligible document passages, read and write own chats, plus reserved image "
                            + "generation and LLM gateway rights. Does not grant administrative capabilities.",
                    false
            ),
            metadata(
                    IamCapability.SEARCH_READ,
                    "Search documents",
                    "Search and read eligible document passages, subject to Source visibility and document ACLs. "
                            + "Derived from Basic access; cannot be granted directly.",
                    false
            ),
            metadata(
                    IamCapability.CHAT_READ,
                    "Read chats",
                    "Read own and shared conversations and follow replies. "
                            + "Derived from Basic access; cannot be granted directly.",
                    false
            ),
            metadata(
                    IamCapability.CHAT_WRITE,
                    "Write chats",
                    "Start conversations, send, edit, regenerate and stop replies. "
                            + "Derived from Basic access; cannot be granted directly.",
                    false
            ),
            metadata(
                    IamCapability.IMAGE_GENERATE,
                    "Generate images",
                    "Reserved for upcoming image generation; not an available or enforced feature permission. "
                            + "Derived from Basic access; cannot be granted directly.",
                    false
            ),
            metadata(
                    IamCapability.LLM_GATEWAY_USE,
                    "Use LLM gateway",
                    "Reserved for upcoming LLM gateway use; not an available or enforced feature permission. "
                            + "Derived from Basic access; cannot be granted directly.",
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
                    "View Groups and their memberships. Derived from Manage groups or Administrator access; "
                            + "cannot be granted directly. Scoped managers can view only the Groups they manage.",
                    false
            ),
            metadata(
                    IamCapability.GROUPS_MANAGE,
                    "Manage groups",
                    "Create Groups and manage ordinary Groups. Scoped managers can rename their Groups, "
                            + "manage existing members, and delegate peer managers without global administration.",
                    true
            ),
            metadata(
                    IamCapability.SOURCES_READ,
                    "View Sources",
                    "Globally view Source configuration and operation history. Derived from Manage Sources "
                            + "or Administrator access; cannot be granted directly. "
                            + "Scoped managers can view public, member-associated, or own nonpublic groupless Sources.",
                    false
            ),
            metadata(
                    IamCapability.SOURCES_MANAGE,
                    "Manage Sources",
                    "Globally view and create Sources, edit Group associations, upload content, reindex, "
                            + "remove Source items, and delete Sources. Scoped managers can manage nonpublic Sources "
                            + "whose Groups they all manage, or their own nonpublic groupless Sources.",
                    true
            ),
            metadata(
                    IamCapability.SOURCES_DELETE,
                    "Delete Sources",
                    "Globally remove Source items or delete Sources. Derived from Manage Sources "
                            + "or Administrator access; cannot be granted directly or exercised by scoped managers.",
                    false
            )
    );

    private final IamAuthorization authorization;
    private final GroupRepository groups;
    private final GroupMembershipRepository memberships;
    private final GroupCapabilityGrantRepository grants;
    private final GroupProjectionRepository projections;
    private final GroupInvariantRepository invariants;
    private final GroupAdministrationGuard administrationGuard;
    private final AuditTrail audit;

    public DefaultGroupService(
            IamAuthorization authorization,
            GroupRepository groups,
            GroupMembershipRepository memberships,
            GroupCapabilityGrantRepository grants,
            GroupProjectionRepository projections,
            GroupInvariantRepository invariants,
            GroupAdministrationGuard administrationGuard,
            AuditTrail audit
    ) {
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
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
        audit.record(AuditRecord.of(AuditAction.GROUP_CREATE, access.tenantId()).actor(requiredActorId)
                .resource("GROUP", group.getId(), requiredName).build());
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
        IamAccess access = authorization.lockAndRequireScopedMutation(
                requiredActorId,
                IamCapability.GROUPS_MANAGE
        );
        GroupEntity group = ordinaryGroup(access.tenantId(), requiredGroupId);
        requireManagedScope(requiredActorId, access, requiredGroupId);
        requireUniqueName(access.tenantId(), requiredName, requiredGroupId);
        String before = group.getName();
        group.rename(requiredName);
        try {
            groups.flush();
        } catch (DataIntegrityViolationException conflict) {
            throw groupConflict(requiredName, conflict);
        }
        if (!before.equals(requiredName)) {
            audit.record(AuditRecord.of(AuditAction.GROUP_RENAME, access.tenantId()).actor(requiredActorId)
                    .resource("GROUP", requiredGroupId.value(), requiredName)
                    .detail("before", before).detail("after", requiredName).build());
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
        requireRetainedGroup(!invariants.deletionLeavesStandardMembersGroupless(
                access.tenantId(), requiredGroupId
        ));
        long members = projections.detail(access.tenantId(), requiredActorId, requiredGroupId, true)
                .map(GroupRecord::memberCount).orElse(0L);
        String name = group.getName();
        groups.remove(group);
        groups.flush();
        audit.record(AuditRecord.of(AuditAction.GROUP_DELETE, access.tenantId()).actor(requiredActorId)
                .resource("GROUP", requiredGroupId.value(), name).detail("memberCount", members).build());
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
                        .contains(IamCapability.SYSTEM_ADMIN)) {
            throw new IamException(
                    IamFailureReason.GROUP_PROTECTED,
                    "System Group membership candidates require SYSTEM_ADMIN"
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
        IamAccess access = authorization.lockAndRequireScopedMutation(requiredActorId, IamCapability.GROUPS_MANAGE);
        GroupEntity group = mutableMembershipGroup(
                requiredActorId,
                access,
                requiredGroupId
        );
        requireDelegable(requiredActorId, group);
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
        var added = new ArrayList<String>();
        for (ActorId memberActorId : requiredMembers) {
            if (!existingMembers.contains(memberActorId)) {
                memberships.add(
                        group,
                        memberships.tenantMembershipReference(access.tenantId(), memberActorId),
                        false
                );
                added.add(audit.person(memberActorId).label());
            }
        }
        memberships.flush();
        if (!added.isEmpty()) {
            added.sort(String.CASE_INSENSITIVE_ORDER);
            audit.record(AuditRecord.of(AuditAction.GROUP_MEMBER_CHANGE, access.tenantId()).actor(requiredActorId)
                    .resource("GROUP", requiredGroupId.value(), group.getName()).detail("added", added).build());
        }
    }

    @Override
    @Transactional
    public void removeMember(ActorId actorId, GroupId groupId, ActorId memberActorId) {
        ActorId requiredActorId = requireActor(actorId);
        GroupId requiredGroupId = requireGroup(groupId);
        ActorId requiredMemberActorId = requireActor(memberActorId);
        IamAccess access = authorization.lockAndRequireScopedMutation(requiredActorId, IamCapability.GROUPS_MANAGE);
        GroupEntity group = mutableMembershipGroup(requiredActorId, access, requiredGroupId);
        GroupMembershipEntity membership = memberships.find(
                access.tenantId(),
                requiredGroupId,
                requiredMemberActorId
        ).orElseThrow(() -> memberNotFound(requiredGroupId, requiredMemberActorId));
        if (access.authority() == Authority.SCOPED && requiredActorId.equals(requiredMemberActorId)) {
            throw new IamException(
                    IamFailureReason.ACCESS_DENIED,
                    "Scoped managers cannot remove their own managed Group membership"
            );
        }
        if (group.getSystemKey() == GroupSystemKey.ADMIN) {
            administrationGuard.requireCanDeactivate(access.tenantId(), requiredMemberActorId);
        }
        requireRetainedGroup(!invariants.removalLeavesStandardMemberGroupless(
                access.tenantId(), requiredGroupId, requiredMemberActorId
        ));
        memberships.remove(membership);
        memberships.flush();
        audit.record(AuditRecord.of(AuditAction.GROUP_MEMBER_CHANGE, access.tenantId()).actor(requiredActorId)
                .resource("GROUP", requiredGroupId.value(), group.getName())
                .detail("removed", List.of(audit.person(requiredMemberActorId).label())).build());
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
        if (!requiredCapabilities.stream().allMatch(IamCapability::isOrdinaryGrant)) {
            throw new IamException(
                    IamFailureReason.GROUP_PROTECTED,
                    "Only ordinary administrative capabilities can be granted directly"
            );
        }
        IamAccess access = authorization.lockAndRequireAdministration(requiredActorId);
        GroupEntity group = ordinaryGroup(access.tenantId(), requiredGroupId);
        Set<IamCapability> before = Set.copyOf(grants.findCapabilities(group));
        grants.replace(group, requiredCapabilities);
        groups.flush();
        if (!before.equals(requiredCapabilities)) {
            audit.record(AuditRecord.of(AuditAction.GROUP_PERMISSION_CHANGE, access.tenantId()).actor(requiredActorId)
                    .resource("GROUP", requiredGroupId.value(), group.getName())
                    .detail("before", names(before)).detail("after", names(requiredCapabilities)).build());
        }
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
        if (requiredGroupIds.isEmpty()) {
            requireRetainedGroup(!invariants.ordinaryReplacementLeavesStandardMemberGroupless(
                    access.tenantId(), requiredMemberActorId
            ));
        }
        List<String> groupsBefore = invariants.ordinaryGroupNames(access.tenantId(), requiredMemberActorId);

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
        List<String> groupsAfter = invariants.ordinaryGroupNames(access.tenantId(), requiredMemberActorId);
        if (!groupsBefore.equals(groupsAfter)) {
            var person = audit.person(requiredMemberActorId);
            audit.record(AuditRecord.of(AuditAction.USER_GROUP_CHANGE, access.tenantId()).actor(requiredActorId)
                    .resource("USER", requiredMemberActorId.value(), person.label()).detail("email", person.email())
                    .detail("added", groupsAfter.stream().filter(name -> !groupsBefore.contains(name)).toList())
                    .detail("removed", groupsBefore.stream().filter(name -> !groupsAfter.contains(name)).toList())
                    .build());
        }
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
        IamAccess access = authorization.lockAndRequireScopedMutation(
                requiredActorId,
                IamCapability.GROUPS_MANAGE
        );
        ordinaryGroup(access.tenantId(), requiredGroupId);
        requireManagedScope(requiredActorId, access, requiredGroupId);
        if (!manager && access.authority() == Authority.SCOPED && requiredActorId.equals(requiredMemberActorId)) {
            throw new IamException(
                    IamFailureReason.ACCESS_DENIED,
                    "Scoped managers cannot revoke their own Group manager scope"
            );
        }
        GroupMembershipEntity membership = memberships.find(
                access.tenantId(),
                requiredGroupId,
                requiredMemberActorId
        ).orElseThrow(() -> memberNotFound(requiredGroupId, requiredMemberActorId));
        boolean changed = membership.isManager() != manager;
        membership.setManager(manager);
        memberships.flush();
        if (changed) {
            var person = audit.person(requiredMemberActorId);
            audit.record(AuditRecord.of(AuditAction.GROUP_MANAGER_CHANGE, access.tenantId()).actor(requiredActorId)
                    .resource("GROUP", requiredGroupId.value(), groups.find(access.tenantId(), requiredGroupId)
                            .map(GroupEntity::getName).orElse(null))
                    .detail("member", person.label()).detail("email", person.email())
                    .detail("manager", manager).build());
        }
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


    private GroupEntity mutableMembershipGroup(
            ActorId actorId,
            IamAccess access,
            GroupId groupId
    ) {
        GroupEntity group = groups.find(access.tenantId(), groupId)
                .orElseThrow(() -> groupNotFound(groupId));
        if (group.isSystemGroup()) {
            if (!authorization.effectiveCapabilities(actorId).contains(IamCapability.SYSTEM_ADMIN)) {
                throw new IamException(
                        IamFailureReason.GROUP_PROTECTED,
                        "System Group memberships require SYSTEM_ADMIN"
                );
            }
            return group;
        }
        requireManagedScope(actorId, access, groupId);
        return group;
    }

    private void requireManagedScope(ActorId actorId, IamAccess access, GroupId groupId) {
        if (access.authority() == Authority.SCOPED
                && !invariants.isManagedBy(access.tenantId(), actorId, groupId)) {
            // A Group manager reaching past the Groups they manage, the refusal Onyx records as permission.denied.
            audit.recordSeparately(AuditRecord.of(AuditAction.PERMISSION_DENIED, access.tenantId())
                    .outcome(AuditOutcome.DENIED).actor(actorId)
                    .resource("GROUP", groupId.value(), groups.find(access.tenantId(), groupId).map(GroupEntity::getName).orElse(null))
                    .detail("capability", IamCapability.GROUPS_MANAGE.name()).detail("scope", "GROUP").build());
            throw groupNotFound(groupId);
        }
    }

    private static List<String> names(Set<IamCapability> capabilities) {
        return capabilities.stream().map(Enum::name).sorted().toList();
    }

    private void requireDelegable(ActorId actorId, GroupEntity group) {
        Set<IamCapability> groupCapabilities = IamCapability.expand(grants.findCapabilities(group));
        Set<IamCapability> managerCapabilities = authorization.effectiveCapabilities(actorId);
        if (!managerCapabilities.contains(IamCapability.SYSTEM_ADMIN)
                && !managerCapabilities.containsAll(groupCapabilities)) {
            throw new IamException(
                    IamFailureReason.MANAGER_AMPLIFICATION_DENIED,
                    "Group administrator lacks at least one expanded capability granted by the target Group"
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
        return new GroupSummary(
                group.id(),
                group.name(),
                group.systemKey(),
                group.memberCount(),
                group.managerCount(),
                group.capabilities(),
                GroupPermissions.of(effectiveCapabilities, group.systemKey() != null, group.managedByActor())
        );
    }

    private static void requireRetainedGroup(boolean retained) {
        if (!retained) {
            throw new IamException(
                    IamFailureReason.LAST_GROUP_PROTECTED,
                    "Mutation would leave a STANDARD Tenant member without any Group"
            );
        }
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
