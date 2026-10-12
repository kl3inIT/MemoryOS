package io.memoryos.objectstorage;

import java.util.Objects;

public final class ObjectStorageException extends RuntimeException {
    private final ObjectStorageFailureCode code;
    private final boolean retryable;

    public ObjectStorageException(ObjectStorageFailureCode code, boolean retryable, Throwable cause) {
        super("Object storage operation failed", cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.retryable = retryable;
    }

    public ObjectStorageFailureCode code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
