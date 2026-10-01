package io.memoryos.ai.openai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.core.http.Headers;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class OpenAiFailuresTest {
    private static final Headers NONE = Headers.builder().build();

    @Test
    void aRefusedCredentialIsRecognisedThroughTheWrappersAroundIt() {
        var unauthorized = UnauthorizedException.builder().headers(NONE).build();
        assertTrue(OpenAiFailures.credentialRejected(unauthorized));
        assertTrue(OpenAiFailures.credentialRejected(PermissionDeniedException.builder().headers(NONE).build()));
        assertTrue(OpenAiFailures.credentialRejected(new IllegalStateException(new CompletionException(unauthorized))));
    }

    @Test
    void anyOtherFailureIsNotARefusedCredential() {
        assertFalse(OpenAiFailures.credentialRejected(RateLimitException.builder().headers(NONE).build()));
        assertFalse(OpenAiFailures.credentialRejected(new IllegalStateException("timeout")));
        assertFalse(OpenAiFailures.credentialRejected(null));
    }
}
