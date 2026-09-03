package io.memoryos.objectstorage;

import java.util.Objects;
import java.util.UUID;

public record StoredObjectId(UUID value) {
    public StoredObjectId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
