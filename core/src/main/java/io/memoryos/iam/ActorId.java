package io.memoryos.iam;

import java.io.Serial;
import java.io.Serializable;

import java.util.Objects;
import java.util.UUID;

public record ActorId(UUID value) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;


    public ActorId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
