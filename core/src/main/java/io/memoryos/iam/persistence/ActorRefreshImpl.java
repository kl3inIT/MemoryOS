package io.memoryos.iam.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Objects;
import java.util.UUID;

public class ActorRefreshImpl implements ActorRefresh {
    private final EntityManager entityManager;

    public ActorRefreshImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public ActorEntity refreshForUpdate(UUID id) {
        var entity = Objects.requireNonNull(entityManager.find(ActorEntity.class, id), "Actor must exist");
        // Acquiring a lock alone does not reload an Actor already present in the persistence context.
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return entity;
    }
}
