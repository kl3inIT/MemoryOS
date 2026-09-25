package io.memoryos.iam;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public record GroupId(UUID value) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public GroupId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
