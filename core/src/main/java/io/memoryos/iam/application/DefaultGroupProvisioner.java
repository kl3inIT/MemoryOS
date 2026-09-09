package io.memoryos.iam.application;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupProvisioner;
import io.memoryos.iam.GroupSystemKey;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.persistence.GroupEntity;
import io.memoryos.iam.persistence.GroupMembershipRepository;
import io.memoryos.iam.persistence.GroupRepository;
import io.memoryos.iam.persistence.TenantEntity;
import io.memoryos.iam.persistence.TenantMembershipEntity;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultGroupProvisioner implements GroupProvisioner {
    private final GroupRepository groups;
    private final GroupMembershipRepository memberships;
    private final GroupCapabilityGrantRepository grants;

    public DefaultGroupProvisioner(
            GroupRepository groups,
            GroupMembershipRepository memberships,
            GroupCapabilityGrantRepository grants
    ) {
        this.groups = Objects.requireNonNull(groups, "groups must not be null");
        this.memberships = Objects.requireNonNull(memberships, "memberships must not be null");
        this.grants = Objects.requireNonNull(grants, "grants must not be null");
    }

    @Override
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
        GroupEntity basic = systemGroup(
                tenant,
                requiredTenantId,
                GroupEntity.BASIC_ID,
                "Basic",
                GroupSystemKey.BASIC
        );
        grants.replace(admin, Set.of(IamCapability.IAM_ADMIN));
        grants.replace(basic, Set.of());

        TenantMembershipEntity ownerMembership = memberships.tenantMembershipReference(
                requiredTenantId,
                requiredOwner
        );
        addIfAbsent(requiredTenantId, admin, requiredOwner, ownerMembership);
        addIfAbsent(requiredTenantId, basic, requiredOwner, ownerMembership);
        memberships.flush();
    }

    @Override
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
