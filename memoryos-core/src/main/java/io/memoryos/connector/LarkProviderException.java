package io.memoryos.connector;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Provider-side Lark failure, classified from the HTTP status and the JSON {@code code} Lark answers with. Lark's own
 * message is never carried: a caller explains the failure from its {@link Failure} and, for a refused scope, from the
 * scope names Lark listed.
 */
public final class LarkProviderException extends RuntimeException {
    public enum Failure {
        /** The App ID or secret was refused, or the tenant token is no longer valid. */
        AUTHENTICATION,
        /** The app lacks a scope for the tenant token, or the object is not shared with it. */
        AUTHORIZATION,
        NOT_FOUND,
        /** Rate limited or out of the plan's monthly calls. */
        QUOTA,
        UNAVAILABLE,
        MALFORMED,
        /** A response or listing exceeded a bound MemoryOS sets. */
        LIMIT_EXCEEDED
    }

    private final Failure failure;
    private final @Nullable Duration retryAfter;
    private final Set<String> missingScopes;

    public LarkProviderException(Failure failure) {
        this(failure, null, Set.of());
    }

    /**
     * @param retryAfter    how long Lark asked the caller to wait, when it said
     * @param missingScopes the tenant-token scopes Lark named as required, for {@link Failure#AUTHORIZATION}
     */
    public LarkProviderException(Failure failure, @Nullable Duration retryAfter, Set<String> missingScopes) {
        super("Lark request failed: " + Objects.requireNonNull(failure, "failure"));
        this.failure = failure;
        this.retryAfter = retryAfter;
        this.missingScopes = Set.copyOf(missingScopes);
    }

    public Failure failure() { return failure; }

    public @Nullable Duration retryAfter() { return retryAfter; }

    public Set<String> missingScopes() { return missingScopes; }
}
