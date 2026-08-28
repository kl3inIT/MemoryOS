package io.memoryos.document;

import java.util.Objects;
import java.util.UUID;

public record DocumentId(UUID value) {
    public DocumentId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
