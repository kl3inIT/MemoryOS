package io.memoryos.objectstorage;

import java.util.Objects;

public record StoredObjectReference(
        StoredObjectId id,
        ObjectKey key,
        String filename,
        ObjectMetadata metadata
) {
    public StoredObjectReference {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(filename, "filename must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
    }
}
