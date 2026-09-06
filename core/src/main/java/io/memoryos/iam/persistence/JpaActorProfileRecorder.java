package io.memoryos.iam.persistence;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.ActorProfileRecorder;
import io.memoryos.iam.ExternalIdentity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaActorProfileRecorder implements ActorProfileRecorder {

    private final EntityManager entityManager;
    private final Clock clock;

    @Autowired
    public JpaActorProfileRecorder(EntityManager entityManager) {
        this(entityManager, Clock.systemUTC());
    }

    JpaActorProfileRecorder(EntityManager entityManager, Clock clock) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    @Transactional
    public void record(
            ActorId actorId,
            ExternalIdentity identity,
            @Nullable String displayName,
            @Nullable String email,
            boolean emailVerified
    ) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(identity, "identity must not be null");

        ExternalIdentityBindingEntity binding = entityManager.find(
                ExternalIdentityBindingEntity.class,
                new ExternalIdentityBindingId(identity.issuer(), identity.subject())
        );
        if (binding == null || !binding.getActor().getId().equals(actorId.value())) {
            throw new IllegalStateException("profile observation requires the actor's exact external identity binding");
        }

        ActorEntity actor = binding.getActor();
        entityManager.lock(actor, LockModeType.PESSIMISTIC_WRITE);
        ActorProfileEntity profile = entityManager.find(ActorProfileEntity.class, actorId.value());
        Instant observedAt = clock.instant();
        if (profile == null) {
            entityManager.persist(new ActorProfileEntity(
                    actor,
                    binding,
                    nullableClaim(displayName),
                    nullableClaim(email),
                    emailVerified,
                    observedAt
            ));
        } else {
            profile.observe(
                    binding,
                    nullableClaim(displayName),
                    nullableClaim(email),
                    emailVerified,
                    observedAt
            );
        }
    }

    private static @Nullable String nullableClaim(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
