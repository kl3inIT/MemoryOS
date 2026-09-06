package io.memoryos.iam.persistence;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.TenantId;

import jakarta.persistence.EntityManager;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Repository;

@Repository
public class GroupMembershipRepository {

    private final EntityManager entityManager;

    public GroupMembershipRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    public Optional<GroupMembershipEntity> find(
            TenantId tenantId,
            GroupId groupId,
            ActorId actorId
    ) {
        return Optional.ofNullable(entityManager.find(
                GroupMembershipEntity.class,
                membershipId(tenantId, groupId, actorId)
        ));
    }

    public TenantMembershipEntity tenantMembershipReference(TenantId tenantId, ActorId actorId) {
        return entityManager.getReference(
                TenantMembershipEntity.class,
                new TenantMembershipId(
                        Objects.requireNonNull(tenantId, "tenantId must not be null").value(),
                        Objects.requireNonNull(actorId, "actorId must not be null").value()
                )
        );
    }

    public void add(GroupEntity group, TenantMembershipEntity tenantMembership, boolean manager) {
        entityManager.persist(new GroupMembershipEntity(group, tenantMembership, manager));
    }

    public void remove(GroupMembershipEntity membership) {
        entityManager.remove(Objects.requireNonNull(membership, "membership must not be null"));
    }

    public void removeOrdinaryMembershipsExcept(
            TenantId tenantId,
            ActorId actorId,
            Collection<GroupId> retainedGroupIds
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(retainedGroupIds, "retainedGroupIds must not be null");
        if (retainedGroupIds.isEmpty()) {
            entityManager.createQuery("""
                            DELETE FROM GroupMembershipEntity membership
                            WHERE membership.id.tenantId = :tenantId
                              AND membership.id.actorId = :actorId
                              AND membership.id.groupId IN (
                                    SELECT groupEntity.entityId.id
                                    FROM GroupEntity groupEntity
                                    WHERE groupEntity.entityId.tenantId = :tenantId
                                      AND groupEntity.systemKey IS NULL
                              )
                            """)
                    .setParameter("tenantId", tenantId.value())
                    .setParameter("actorId", actorId.value())
                    .executeUpdate();
            return;
        }
        entityManager.createQuery("""
                        DELETE FROM GroupMembershipEntity membership
                        WHERE membership.id.tenantId = :tenantId
                          AND membership.id.actorId = :actorId
                          AND membership.id.groupId IN (
                                SELECT groupEntity.entityId.id
                                FROM GroupEntity groupEntity
                                WHERE groupEntity.entityId.tenantId = :tenantId
                                  AND groupEntity.systemKey IS NULL
                          )
                          AND membership.id.groupId NOT IN :retainedGroupIds
                        """)
                .setParameter("tenantId", tenantId.value())
                .setParameter("actorId", actorId.value())
                .setParameter("retainedGroupIds", retainedGroupIds.stream().map(GroupId::value).toList())
                .executeUpdate();
    }

    public void flush() {
        entityManager.flush();
    }

    private static GroupMembershipId membershipId(
            TenantId tenantId,
            GroupId groupId,
            ActorId actorId
    ) {
        return new GroupMembershipId(
                Objects.requireNonNull(tenantId, "tenantId must not be null").value(),
                Objects.requireNonNull(groupId, "groupId must not be null").value(),
                Objects.requireNonNull(actorId, "actorId must not be null").value()
        );
    }
}
