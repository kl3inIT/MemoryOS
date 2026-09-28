package io.memoryos.ai;

import java.util.Objects;
import java.util.Optional;

/**
 * A model turn stopped for a {@link TurnFailure}. Its message is the code, never provider or prompt content.
 *
 * <p>It is an {@link IllegalStateException} because Embabel's retry policy treats that as non-retryable: a spent
 * budget or a passed deadline must not be retried. It is deliberately not a {@code BusinessException}: it is not an
 * HTTP problem, and a meeting's minutes job records any business code it sees.
 */
public final class TurnFailureException extends IllegalStateException {
    private static final int MAX_CAUSES = 8;

    private final TurnFailure failure;

    public TurnFailureException(TurnFailure failure) {
        super(Objects.requireNonNull(failure, "failure must not be null").code());
        this.failure = failure;
    }

    public TurnFailure failure() {
        return failure;
    }

    public String code() {
        return failure.code();
    }

    /** The first reported turn failure in {@code failure} or its causes; an unreported one is passed over. */
    public static Optional<TurnFailure> reportedIn(Throwable failure) {
        for (int depth = 0; failure != null && depth < MAX_CAUSES; depth++, failure = failure.getCause()) {
            if (failure instanceof TurnFailureException turn && turn.failure.reported()) return Optional.of(turn.failure);
        }
        return Optional.empty();
    }
}
