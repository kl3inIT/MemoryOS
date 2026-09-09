package io.memoryos.connector;

import io.memoryos.objectstorage.StoredObjectReference;

import java.util.Objects;

public record CleanupObject(StoredObjectReference object) {
    public CleanupObject {
        Objects.requireNonNull(object, "object must not be null");
    }
}
