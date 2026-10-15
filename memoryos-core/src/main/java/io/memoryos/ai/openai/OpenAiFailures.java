package io.memoryos.ai.openai;

import com.openai.errors.OpenAIServiceException;
import io.memoryos.ai.TurnFailure;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** How this protocol's failures read; the SDK exception itself never leaves the adapter as a message. */
final class OpenAiFailures {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAiFailures.class);
    private static final int MAX_CAUSES = 8;

    private OpenAiFailures() {
    }

    /**
     * MEM-255: records why the endpoint refused a request (HTTP status and OpenAI's {@code type}, {@code code} and
     * {@code param}), which the turn's failure code alone cannot say. Never the message or body: they can quote the
     * request.
     */
    static void logRefusal(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSES; depth++, cause = cause.getCause()) {
            if (cause instanceof OpenAIServiceException service) {
                LOG.atWarn().addKeyValue("event", "ai.provider.refused").addKeyValue("status", service.statusCode())
                        .addKeyValue("provider_type", service.type().orElse(""))
                        .addKeyValue("provider_code", service.code().orElse(""))
                        .addKeyValue("provider_param", service.param().orElse(""))
                        .addKeyValue("error_type", service.getClass().getSimpleName())
                        .log("The model endpoint refused the request");
                return;
            }
        }
        LOG.atWarn().addKeyValue("event", "ai.provider.refused").addKeyValue("error_type", failure.getClass().getSimpleName())
                .log("The model endpoint call failed");
    }

    /**
     * MEM-255: what a failure means for the turn. A refused credential is HTTP 401 or 403; a rate limit is HTTP 429 or a
     * {@code rate_limit_exceeded} error inside the stream, which arrives with HTTP 200 (an exhausted quota,
     * {@code insufficient_quota}, is not a limit that passes and stays unnamed).
     */
    static Optional<TurnFailure> read(@Nullable Throwable failure) {
        for (int depth = 0; failure != null && depth < MAX_CAUSES; depth++, failure = failure.getCause()) {
            if (failure instanceof OpenAIServiceException service) {
                if (service.statusCode() == 401 || service.statusCode() == 403)
                    return Optional.of(TurnFailure.PROVIDER_CREDENTIAL_REJECTED);
                String code = service.code().orElse("");
                if ("rate_limit_exceeded".equals(code) || service.statusCode() == 429 && !"insufficient_quota".equals(code))
                    return Optional.of(TurnFailure.PROVIDER_RATE_LIMITED);
            }
        }
        return Optional.empty();
    }
}
