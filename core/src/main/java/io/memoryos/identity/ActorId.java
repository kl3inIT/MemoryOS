package io.memoryos.identity;

import java.util.Objects;
import java.util.UUID;

public record ActorId(UUID value) {

    public ActorId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
