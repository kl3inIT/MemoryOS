package io.memoryos.connector;

import java.util.Objects;

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

    public SharePointProviderException(Failure failure) {
        this(failure, Reason.UNCLASSIFIED);
    }

    public SharePointProviderException(Failure failure, Reason reason) {
        super("SharePoint request failed: " + Objects.requireNonNull(failure, "failure")
                + "/" + Objects.requireNonNull(reason, "reason"));
        this.failure = failure;
        this.reason = reason;
    }

    public Failure failure() { return failure; }

    public Reason reason() { return reason; }
}
