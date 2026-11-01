package io.memoryos.connector;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

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
    private final @Nullable Duration retryAfter;

    public GoogleDriveProviderException(Failure failure) {
        this(failure, null);
    }

    /** {@code retryAfter} is how long Google asked the caller to wait before trying again, when it said. */
    public GoogleDriveProviderException(Failure failure, @Nullable Duration retryAfter) {
        super("Google acquisition failed: " + Objects.requireNonNull(failure, "failure"));
        this.failure = failure;
        this.retryAfter = retryAfter;
    }

    public Failure failure() { return failure; }

    /** The wait Google asked for with a {@code Retry-After} header on a throttled or unavailable response. */
    public @Nullable Duration retryAfter() { return retryAfter; }

    /** The credential cannot serve any further request until the Google account is reconnected. */
    public boolean requiresReconnect() {
        return failure == Failure.AUTHENTICATION || failure == Failure.SCOPE_INSUFFICIENT;
    }
}
