package io.memoryos.connector;

import java.util.Objects;
import java.util.UUID;

public record SourceId(UUID value) {
    public SourceId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
