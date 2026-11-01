package io.memoryos.connector;

import java.util.Objects;
import java.util.UUID;

public record SourceItemId(UUID value) {
    public SourceItemId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
