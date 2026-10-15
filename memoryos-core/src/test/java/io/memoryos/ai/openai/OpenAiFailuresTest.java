package io.memoryos.ai.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.SseException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.ErrorObject;
import io.memoryos.ai.TurnFailure;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class OpenAiFailuresTest {
    private static final Headers NONE = Headers.builder().build();

    @Test
    void aRefusedCredentialIsRecognisedThroughTheWrappersAroundIt() {
        var unauthorized = UnauthorizedException.builder().headers(NONE).build();
        assertEquals(Optional.of(TurnFailure.PROVIDER_CREDENTIAL_REJECTED), OpenAiFailures.read(unauthorized));
        assertEquals(Optional.of(TurnFailure.PROVIDER_CREDENTIAL_REJECTED),
                OpenAiFailures.read(PermissionDeniedException.builder().headers(NONE).build()));
        assertEquals(Optional.of(TurnFailure.PROVIDER_CREDENTIAL_REJECTED),
                OpenAiFailures.read(new IllegalStateException(new CompletionException(unauthorized))));
    }

    /** MEM-255: OpenAI refuses an over-limit stream with HTTP 200 and the error inside the stream. */
    @Test
    void aRateLimitIsRecognisedAsAnHttpStatusAndInsideTheStream() {
        assertEquals(Optional.of(TurnFailure.PROVIDER_RATE_LIMITED),
                OpenAiFailures.read(RateLimitException.builder().headers(NONE).build()));
        var inStream = SseException.builder().statusCode(200).headers(NONE)
                .error(error("rate_limit_exceeded", "tokens")).build();
        assertEquals(Optional.of(TurnFailure.PROVIDER_RATE_LIMITED), OpenAiFailures.read(new CompletionException(inStream)));
    }

    @Test
    void billingAndAnyOtherFailureAreNotNamed() {
        for (var billing : new String[] {"insufficient_quota", "credit_balance_exhausted", "organization_spend_limit_exceeded"}) {
            var refused = SseException.builder().statusCode(429).headers(NONE).error(error(billing, billing)).build();
            assertEquals(Optional.empty(), OpenAiFailures.read(refused), billing);
        }
        assertEquals(Optional.empty(), OpenAiFailures.read(InternalServerException.builder().statusCode(500).headers(NONE).build()));
        assertEquals(Optional.empty(), OpenAiFailures.read(new IllegalStateException("timeout")));
        assertEquals(Optional.empty(), OpenAiFailures.read(null));
    }

    private static ErrorObject error(String code, String type) {
        return ErrorObject.builder().code(code).message("refused").param((String) null).type(type).build();
    }
}
