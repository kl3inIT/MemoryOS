package io.memoryos.iam.group;

import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupSystemKey;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.shared.TenantId;
import io.memoryos.iam.group.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.group.persistence.GroupEntity;
import io.memoryos.iam.group.persistence.GroupMembershipRepository;
import io.memoryos.iam.group.persistence.GroupRepository;
import io.memoryos.iam.tenant.persistence.TenantEntity;
import io.memoryos.iam.tenant.persistence.TenantMembershipEntity;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupProvisioner {
    private final GroupRepository groups;
    private final GroupMembershipRepository memberships;
    private final GroupCapabilityGrantRepository grants;

    public GroupProvisioner(
            GroupRepository groups,
            GroupMembershipRepository memberships,
            GroupCapabilityGrantRepository grants
    ) {
        this.groups = Objects.requireNonNull(groups, "groups must not be null");
        this.memberships = Objects.requireNonNull(memberships, "memberships must not be null");
        this.grants = Objects.requireNonNull(grants, "grants must not be null");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bootstrap(TenantId tenantId, ActorId configuredOwner) {
        TenantId requiredTenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        ActorId requiredOwner = Objects.requireNonNull(configuredOwner, "configuredOwner must not be null");
        TenantEntity tenant = groups.tenantReference(requiredTenantId);

        GroupEntity admin = systemGroup(
                tenant,
                requiredTenantId,
                GroupEntity.ADMIN_ID,
                "Admin",
                GroupSystemKey.ADMIN
        );
        boolean basicExisted = groups.findSystem(requiredTenantId, GroupSystemKey.BASIC).isPresent();
        GroupEntity basic = systemGroup(
                tenant,
                requiredTenantId,
                GroupEntity.BASIC_ID,
                "Basic",
                GroupSystemKey.BASIC
        );
        grants.replace(admin, Set.of(IamCapability.SYSTEM_ADMIN));
        grants.replace(basic, basicGrants(basicExisted ? grants.findCapabilities(basic) : null));

        TenantMembershipEntity ownerMembership = memberships.tenantMembershipReference(
                requiredTenantId,
                requiredOwner
        );
        addIfAbsent(requiredTenantId, admin, requiredOwner, ownerMembership);
        addIfAbsent(requiredTenantId, basic, requiredOwner, ownerMembership);
        memberships.flush();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void addToBasicGroup(TenantId tenantId, ActorId actorId) {
        TenantId requiredTenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        ActorId requiredActorId = Objects.requireNonNull(actorId, "actorId must not be null");
        GroupEntity basic = groups.findSystem(requiredTenantId, GroupSystemKey.BASIC)
                .orElseThrow(() -> new IamException(
                        IamFailureReason.GROUP_CONFLICT,
                        "The Tenant Basic Group has not been provisioned"
                ));
        TenantMembershipEntity tenantMembership = memberships.tenantMembershipReference(
                requiredTenantId,
                requiredActorId
        );
        addIfAbsent(requiredTenantId, basic, requiredActorId, tenantMembership);
        memberships.flush();
    }

    /**
     * SYSTEM_BASIC with the optional grants a new Basic Group starts with, or, on a re-run, with the ones it holds, so an
     * administrator's choice survives every start.
     */
    private static Set<IamCapability> basicGrants(@Nullable Set<IamCapability> held) {
        EnumSet<IamCapability> result = EnumSet.of(IamCapability.SYSTEM_BASIC);
        for (IamCapability capability : IamCapability.values()) {
            if (capability.isBasicOptionalGrant() && (held == null || held.contains(capability))) result.add(capability);
        }
        return result;
    }

    private GroupEntity systemGroup(
            TenantEntity tenant,
            TenantId tenantId,
            UUID id,
            String name,
            GroupSystemKey systemKey
    ) {
        return groups.findSystem(tenantId, systemKey).orElseGet(() -> {
            GroupEntity group = new GroupEntity(tenant, id, name, systemKey);
            groups.persist(group);
            return group;
        });
    }

    private void addIfAbsent(
            TenantId tenantId,
            GroupEntity group,
            ActorId actorId,
            TenantMembershipEntity tenantMembership
    ) {
        if (memberships.find(tenantId, new GroupId(group.getId()), actorId).isEmpty()) {
            memberships.add(group, tenantMembership, false);
        }
    }
}
