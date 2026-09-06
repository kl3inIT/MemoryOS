package io.memoryos.iam.persistence;

import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupSystemKey;
import io.memoryos.iam.TenantId;

import jakarta.persistence.EntityManager;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;

@Repository
public class GroupRepository {

    private final EntityManager entityManager;

    public GroupRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    public GroupEntity tenantGroupReference(TenantId tenantId, GroupId groupId) {
        return entityManager.getReference(GroupEntity.class, entityId(tenantId, groupId));
    }

    public TenantEntity tenantReference(TenantId tenantId) {
        return entityManager.getReference(
                TenantEntity.class,
                Objects.requireNonNull(tenantId, "tenantId must not be null").value()
        );
    }

    public Optional<GroupEntity> find(TenantId tenantId, GroupId groupId) {
        return Optional.ofNullable(entityManager.find(GroupEntity.class, entityId(tenantId, groupId)));
    }

    public Optional<GroupEntity> findSystem(TenantId tenantId, GroupSystemKey systemKey) {
        Objects.requireNonNull(systemKey, "systemKey must not be null");
        UUID id = switch (systemKey) {
            case ADMIN -> GroupEntity.ADMIN_ID;
            case BASIC -> GroupEntity.BASIC_ID;
        };
        return find(tenantId, new GroupId(id))
                .filter(group -> group.getSystemKey() == systemKey);
    }

    public boolean nameExists(TenantId tenantId, String name, @Nullable GroupId excludedGroupId) {
        StringBuilder query = new StringBuilder("""
                SELECT COUNT(groupEntity)
                FROM GroupEntity groupEntity
                WHERE groupEntity.entityId.tenantId = :tenantId
                  AND LOWER(groupEntity.name) = LOWER(:name)
                """);
        if (excludedGroupId != null) {
            query.append(" AND groupEntity.entityId.id <> :excludedGroupId");
        }
        var typedQuery = entityManager.createQuery(query.toString(), Long.class)
                .setParameter("tenantId", tenantId.value())
                .setParameter("name", name);
        if (excludedGroupId != null) {
            typedQuery.setParameter("excludedGroupId", excludedGroupId.value());
        }
        return typedQuery.getSingleResult() != 0;
    }

    public void persist(GroupEntity group) {
        entityManager.persist(Objects.requireNonNull(group, "group must not be null"));
    }

    public void remove(GroupEntity group) {
        entityManager.remove(Objects.requireNonNull(group, "group must not be null"));
    }

    public void flush() {
        entityManager.flush();
    }

    private static GroupEntityId entityId(TenantId tenantId, GroupId groupId) {
        return new GroupEntityId(
                Objects.requireNonNull(tenantId, "tenantId must not be null").value(),
                Objects.requireNonNull(groupId, "groupId must not be null").value()
        );
    }
}
