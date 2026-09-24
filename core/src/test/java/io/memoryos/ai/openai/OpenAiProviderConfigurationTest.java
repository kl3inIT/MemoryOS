package io.memoryos.ai.openai;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.memoryos.chat.ChatExecutionProperties;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpenAiProviderConfigurationTest {
    private final OpenAiProviderConfiguration configuration = new OpenAiProviderConfiguration();
    private final ChatExecutionProperties limits = new ChatExecutionProperties(1, Duration.ofMinutes(30), Duration.ofSeconds(60), Duration.ofSeconds(30),
            6, 1024, 32000, 10000, null, null, 10, Duration.ofSeconds(60));

    @ParameterizedTest
    @ValueSource(strings = {"ftp://api.example.test/v1", "/v1",
            "https://user:secret@api.example.test/v1", "https://api.example.test/v1?key=secret",
            "https://api.example.test/v1#secret", "https://bad host/secret"})
    void rejectsUnsafeEndpointsBeforeEitherClientIsConstructed(String endpoint) {
        var async = assertThrows(IllegalArgumentException.class,
                () -> configuration.chatOpenAiClient("test-key", endpoint, limits.providerReadTimeout()));
        var sync = assertThrows(IllegalArgumentException.class,
                () -> configuration.chatOpenAiSyncClient("test-key", endpoint, limits.providerReadTimeout()));
        assertFalse(async.getMessage().contains("secret"));
        assertFalse(sync.getMessage().contains("secret"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.example.test/v1", "http://model.internal:8000/v1"})
    void configuredHttpOrHttpsEndpointBuildsBothClientsWithoutMakingARequest(String endpoint) {
        var async = configuration.chatOpenAiClient("test-key", endpoint, limits.providerReadTimeout());
        try {
            assertNotNull(async);
            var sync = configuration.chatOpenAiSyncClient("test-key", endpoint, limits.providerReadTimeout());
            try { assertNotNull(sync); }
            finally { sync.close(); }
        } finally { async.close(); }
    }
}
