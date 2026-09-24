package io.memoryos.iam.identity;

import io.memoryos.shared.ActorId;

import io.memoryos.iam.identity.persistence.ActorProfileEntity;
import jakarta.persistence.EntityManager;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The name and email the identity provider last reported for an Actor, for display and the Chat prompt. */
@Service
public class ActorProfileReader {
    public record Profile(@Nullable String displayName, @Nullable String email) {
        public static final Profile EMPTY = new Profile(null, null);
    }

    private final EntityManager entities;

    public ActorProfileReader(EntityManager entities) {
        this.entities = entities;
    }

    @Transactional(readOnly = true)
    public Profile read(ActorId actor) {
        var profile = entities.find(ActorProfileEntity.class, actor.value());
        return profile == null ? Profile.EMPTY : new Profile(profile.getDisplayName(), profile.getEmail());
    }
}
