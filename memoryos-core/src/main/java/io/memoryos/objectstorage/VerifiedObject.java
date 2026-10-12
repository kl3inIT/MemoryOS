package io.memoryos.objectstorage;

import java.util.Objects;

public record VerifiedObject(
        ObjectUploadId uploadId,
        StoredObjectReference object,
        ObjectVerificationToken token
) {
    public VerifiedObject {
        Objects.requireNonNull(uploadId, "uploadId must not be null");
        Objects.requireNonNull(object, "object must not be null");
        Objects.requireNonNull(token, "token must not be null");
    }
}
