package io.memoryos.iam.persistence;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.ExternalIdentityRegistrar;
import io.memoryos.iam.ExternalIdentityResolver;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaExternalIdentityRegistry implements ExternalIdentityResolver, ExternalIdentityRegistrar {

    private final EntityManager entityManager;

    public JpaExternalIdentityRegistry(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActorId> resolve(ExternalIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        return findBinding(identity).map(binding -> new ActorId(binding.getActor().getId()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ActorId resolveOrCreate(ExternalIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        ActorEntity actor = findBinding(identity)
                .map(ExternalIdentityBindingEntity::getActor)
                .orElseGet(() -> create(identity));
        return new ActorId(actor.getId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ActorId resolveOrCreateLocked(ExternalIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        ActorEntity actor = findBinding(identity)
                .map(ExternalIdentityBindingEntity::getActor)
                .orElseGet(() -> create(identity));
        entityManager.flush();
        entityManager.lock(actor, LockModeType.PESSIMISTIC_WRITE);
        return new ActorId(actor.getId());
    }

    private Optional<ExternalIdentityBindingEntity> findBinding(ExternalIdentity identity) {
        return Optional.ofNullable(entityManager.find(
                ExternalIdentityBindingEntity.class,
                new ExternalIdentityBindingId(identity.issuer(), identity.subject())
        ));
    }

    private ActorEntity create(ExternalIdentity identity) {
        ActorEntity actor = new ActorEntity(UUID.randomUUID());
        entityManager.persist(actor);
        entityManager.persist(new ExternalIdentityBindingEntity(
                new ExternalIdentityBindingId(identity.issuer(), identity.subject()),
                actor
        ));
        return actor;
    }
}
