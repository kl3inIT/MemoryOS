package io.memoryos.chat.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;

/** Advances a write-locked Persona revision when a mutation changes only its relations (shares, tools, labels). */
@Component
public class PersonaRevisions {
    private final EntityManager entities;

    public PersonaRevisions(EntityManager entities) { this.entities = entities; }

    public void advance(PersonaEntity locked) {
        entities.lock(locked, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }
}
