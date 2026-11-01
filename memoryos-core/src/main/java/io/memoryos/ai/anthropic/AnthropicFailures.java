package io.memoryos.ai.anthropic;

import com.anthropic.errors.AnthropicServiceException;
import io.memoryos.ai.TurnFailure;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** How this protocol's failures read; the SDK exception itself never leaves the adapter as a message. */
final class AnthropicFailures {
    private static final int MAX_CAUSES = 8;

    private AnthropicFailures() {
    }

    /** MEM-255: a refused credential is HTTP 401 or 403, a rate limit HTTP 429 (Anthropic's {@code rate_limit_error}). */
    static Optional<TurnFailure> read(@Nullable Throwable failure) {
        for (int depth = 0; failure != null && depth < MAX_CAUSES; depth++, failure = failure.getCause()) {
            if (failure instanceof AnthropicServiceException service) {
                if (service.statusCode() == 401 || service.statusCode() == 403)
                    return Optional.of(TurnFailure.PROVIDER_CREDENTIAL_REJECTED);
                if (service.statusCode() == 429) return Optional.of(TurnFailure.PROVIDER_RATE_LIMITED);
            }
        }
        return Optional.empty();
    }
}
