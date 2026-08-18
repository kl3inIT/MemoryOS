package io.memoryos.identity;

import java.util.Objects;

public record IdentityContext(ActorId actorId) {

    public IdentityContext {
        Objects.requireNonNull(actorId, "actorId must not be null");
    }
}
