package io.memoryos.connector;

import java.util.Objects;

public final class GoogleDriveProviderException extends RuntimeException {
    public enum Failure {
        AUTHENTICATION,
        /** The grant lacks a required OAuth scope; every request fails until the account is reconnected. */
        SCOPE_INSUFFICIENT,
        /** The connected account may not read a file's sharing settings; returned only by permission listing. */
        ACCESS_DENIED,
        NOT_FOUND, QUOTA, UNAVAILABLE, UNSUPPORTED,
        MALFORMED, LIMIT_EXCEEDED, INCONSISTENT
    }

    private final Failure failure;

    public GoogleDriveProviderException(Failure failure) {
        super("Google acquisition failed: " + Objects.requireNonNull(failure, "failure"));
        this.failure = failure;
    }

    public Failure failure() { return failure; }

    /** The credential cannot serve any further request until the Google account is reconnected. */
    public boolean requiresReconnect() {
        return failure == Failure.AUTHENTICATION || failure == Failure.SCOPE_INSUFFICIENT;
    }
}
