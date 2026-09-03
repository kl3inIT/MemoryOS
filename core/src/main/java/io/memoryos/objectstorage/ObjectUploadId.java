package io.memoryos.objectstorage;

import java.util.Objects;
import java.util.UUID;

public record ObjectUploadId(UUID value) {
    public ObjectUploadId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
