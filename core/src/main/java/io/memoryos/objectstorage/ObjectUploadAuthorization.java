package io.memoryos.objectstorage;

import java.util.Objects;

public record ObjectUploadAuthorization(ObjectUploadId uploadId, UploadAuthorization authorization) {
    public ObjectUploadAuthorization {
        Objects.requireNonNull(uploadId, "uploadId must not be null");
        Objects.requireNonNull(authorization, "authorization must not be null");
    }
}
