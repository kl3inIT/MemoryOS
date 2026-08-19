package io.memoryos.identity;

import java.io.Serial;
import java.io.Serializable;

import java.util.Objects;

public record IdentityContext(ActorId actorId) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;


    public IdentityContext {
        Objects.requireNonNull(actorId, "actorId must not be null");
    }
}
