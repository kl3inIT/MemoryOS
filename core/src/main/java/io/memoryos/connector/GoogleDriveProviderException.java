package io.memoryos.connector;

import java.util.Objects;

public final class GoogleDriveProviderException extends RuntimeException {
    public enum Failure {
        AUTHENTICATION, NOT_FOUND, QUOTA, UNAVAILABLE, UNSUPPORTED,
        MALFORMED, LIMIT_EXCEEDED, INCONSISTENT
    }

    private final Failure failure;

    public GoogleDriveProviderException(Failure failure) {
        super("Google acquisition failed: " + Objects.requireNonNull(failure, "failure"));
        this.failure = failure;
    }

    public Failure failure() { return failure; }
}
