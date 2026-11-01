package io.memoryos.connector;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Provider-side SharePoint failure. {@link Reason} carries the classified Entra rejection so the
 * application layer can explain what an administrator must change without echoing Microsoft text.
 */
public final class SharePointProviderException extends RuntimeException {
    public enum Failure {
        AUTHENTICATION, AUTHORIZATION, NOT_FOUND, RESYNC_REQUIRED, QUOTA, UNAVAILABLE, MALFORMED, LIMIT_EXCEEDED
    }

    public enum Reason {
        INVALID_CLIENT_SECRET,
        EXPIRED_CLIENT_SECRET,
        CERTIFICATE_NOT_REGISTERED,
        DIRECTORY_NOT_FOUND,
        APPLICATION_NOT_FOUND,
        CONSENT_REQUIRED,
        UNCLASSIFIED
    }

    private final Failure failure;
    private final Reason reason;
    private final @Nullable Duration retryAfter;

    public SharePointProviderException(Failure failure) {
        this(failure, Reason.UNCLASSIFIED);
    }

    public SharePointProviderException(Failure failure, Reason reason) {
        this(failure, reason, null);
    }

    /** {@code retryAfter} is how long Microsoft asked the caller to wait before trying again, when it said. */
    public SharePointProviderException(Failure failure, Reason reason, @Nullable Duration retryAfter) {
        super("SharePoint request failed: " + Objects.requireNonNull(failure, "failure")
                + "/" + Objects.requireNonNull(reason, "reason"));
        this.failure = failure;
        this.reason = reason;
        this.retryAfter = retryAfter;
    }

    public Failure failure() { return failure; }

    public Reason reason() { return reason; }

    /** The wait Microsoft asked for with a {@code Retry-After} header on a throttled or unavailable response. */
    public @Nullable Duration retryAfter() { return retryAfter; }
}
