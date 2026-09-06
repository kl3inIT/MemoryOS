package io.memoryos.iam.persistence;

import io.memoryos.iam.IamCapability;
import io.memoryos.iam.GroupSystemKey;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;

import jakarta.persistence.EntityManager;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Repository;

@Repository
public class GroupCapabilityGrantRepository {

    private final EntityManager entityManager;

    public GroupCapabilityGrantRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    public Set<IamCapability> findCapabilities(GroupEntity group) {
        var capabilities = entityManager.createQuery("""
                        SELECT grant.id.capability
                        FROM GroupCapabilityGrantEntity grant
                        WHERE grant.id.tenantId = :tenantId
                          AND grant.id.groupId = :groupId
                        """, IamCapability.class)
                .setParameter("tenantId", group.getTenantId())
                .setParameter("groupId", group.getId())
                .getResultList();
        return capabilities.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(capabilities));
    }

    public void replace(GroupEntity group, Collection<IamCapability> capabilities) {
        Objects.requireNonNull(group, "group must not be null");
        Set<IamCapability> requested = Set.copyOf(
                Objects.requireNonNull(capabilities, "capabilities must not be null")
        );
        requireAllowedGrants(group, requested);
        entityManager.createQuery("""
                        DELETE FROM GroupCapabilityGrantEntity grant
                        WHERE grant.id.tenantId = :tenantId
                          AND grant.id.groupId = :groupId
                        """)
                .setParameter("tenantId", group.getTenantId())
                .setParameter("groupId", group.getId())
                .executeUpdate();
        requested.forEach(capability -> entityManager.persist(
                new GroupCapabilityGrantEntity(group, capability)
        ));
    }

    private static void requireAllowedGrants(GroupEntity group, Set<IamCapability> capabilities) {
        GroupSystemKey systemKey = group.getSystemKey();
        boolean valid = switch (systemKey) {
            case ADMIN -> capabilities.equals(Set.of(IamCapability.IAM_ADMIN));
            case BASIC -> capabilities.isEmpty();
            case null -> !capabilities.contains(IamCapability.IAM_ADMIN);
        };
        if (!valid) {
            throw new IamException(
                    IamFailureReason.GROUP_PROTECTED,
                    "IAM_ADMIN is reserved to Admin and Basic cannot receive capability grants"
            );
        }
    }
}
