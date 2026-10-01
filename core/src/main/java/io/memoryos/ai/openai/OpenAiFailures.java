package io.memoryos.ai.openai;

import com.openai.errors.OpenAIServiceException;
import org.jspecify.annotations.Nullable;

/** How this protocol's failures read; the SDK exception itself never leaves the adapter as a message. */
final class OpenAiFailures {
    private static final int MAX_CAUSES = 8;

    private OpenAiFailures() {
    }

    /** Whether the endpoint refused the credential (HTTP 401 or 403), in {@code failure} or one of its causes. */
    static boolean credentialRejected(@Nullable Throwable failure) {
        for (int depth = 0; failure != null && depth < MAX_CAUSES; depth++, failure = failure.getCause()) {
            if (failure instanceof OpenAIServiceException service
                    && (service.statusCode() == 401 || service.statusCode() == 403)) return true;
        }
        return false;
    }
}
