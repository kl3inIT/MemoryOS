package io.memoryos.connector;

import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.StoredObjectReference;

import java.util.Objects;

public record CleanupObject(ObjectUploadId uploadId, StoredObjectReference object) {
    public CleanupObject {
        Objects.requireNonNull(uploadId, "uploadId must not be null");
        Objects.requireNonNull(object, "object must not be null");
    }
}
