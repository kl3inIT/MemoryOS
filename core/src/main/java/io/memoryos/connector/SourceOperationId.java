package io.memoryos.connector;

import java.util.Objects;
import java.util.UUID;

public record SourceOperationId(UUID value) {
    public SourceOperationId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
