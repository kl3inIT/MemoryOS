package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.memoryos.chat.execution.ChatExecutionProperties;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpenAiChatProviderConfigurationTest {
    private final OpenAiChatProviderConfiguration configuration = new OpenAiChatProviderConfiguration();
    private final ChatExecutionProperties limits = new ChatExecutionProperties(1, Duration.ofSeconds(30),
            6, 1024, 32000, 10000, Integer.MAX_VALUE, Double.MAX_VALUE);

    @ParameterizedTest
    @ValueSource(strings = {"ftp://api.example.test/v1", "/v1",
            "https://user:secret@api.example.test/v1", "https://api.example.test/v1?key=secret",
            "https://api.example.test/v1#secret", "https://bad host/secret"})
    void rejectsUnsafeEndpointsBeforeEitherClientIsConstructed(String endpoint) {
        var async = assertThrows(IllegalArgumentException.class,
                () -> configuration.chatOpenAiClient("test-key", endpoint, limits));
        var sync = assertThrows(IllegalArgumentException.class,
                () -> configuration.chatOpenAiSyncClient("test-key", endpoint, limits));
        assertFalse(async.getMessage().contains("secret"));
        assertFalse(sync.getMessage().contains("secret"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.example.test/v1", "http://model.internal:8000/v1"})
    void configuredHttpOrHttpsEndpointBuildsBothClientsWithoutMakingARequest(String endpoint) {
        var async = configuration.chatOpenAiClient("test-key", endpoint, limits);
        try {
            assertNotNull(async);
            var sync = configuration.chatOpenAiSyncClient("test-key", endpoint, limits);
            try { assertNotNull(sync); }
            finally { sync.close(); }
        } finally { async.close(); }
    }
}
