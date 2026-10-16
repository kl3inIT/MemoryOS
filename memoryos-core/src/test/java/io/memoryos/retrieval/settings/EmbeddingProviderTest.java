package io.memoryos.retrieval.settings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.retrieval.SearchUnavailableException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmbeddingProviderTest {
    private static final String CATALOG_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void endpointsFollowTheChatProviderPolicyInternalHttpIncluded() {
        for (String endpoint : List.of("http://172.24.244.79:18090/v1", "http://tei:8080/v1", "https://api.openai.com/v1",
                "http://localhost:11434/v1")) {
            assertDoesNotThrow(() -> EmbeddingProvider.validateEndpoint(endpoint), endpoint);
        }
        for (String endpoint : List.of("https://user:secret@api.openai.com/v1", "https://api.openai.com/v1?api_key=secret",
                "https://api.openai.com/v1#key", "ftp://api.openai.com/v1", "/v1", "not a url")) {
            assertThrows(IllegalArgumentException.class, () -> EmbeddingProvider.validateEndpoint(endpoint), endpoint);
        }
    }

    @Test
    void aKeyIsStoredEncryptedBoundToItsTenantAndProviderAndNeverPrinted() {
        var credentials = new EmbeddingProviderCredentials(CATALOG_KEY);
        UUID tenant = UUID.randomUUID(), provider = UUID.randomUUID();
        String sealed = credentials.seal(tenant, provider, "tei-key");
        assertTrue(sealed.startsWith("v1:"));
        assertFalse(sealed.contains("tei-key"));
        assertEquals("tei-key", credentials.resolve(tenant, provider, sealed));
        assertThrows(SearchUnavailableException.class, () -> credentials.resolve(tenant, UUID.randomUUID(), sealed));
        assertThrows(SearchUnavailableException.class, () -> credentials.resolve(UUID.randomUUID(), provider, sealed));
        assertThrows(SearchUnavailableException.class, () -> new EmbeddingProviderCredentials("")
                .resolve(tenant, provider, sealed));
        assertEquals("", credentials.resolve(tenant, provider, null));
        assertFalse(credentials.present(null));
        assertTrue(credentials.present(sealed));
        // The deployment's key is gone (MEM-216): the reference older releases stored is no key, never sent.
        assertThrows(SearchUnavailableException.class, () -> credentials.resolve(tenant, provider, "deployment"));
        var stored = new EmbeddingProvider(provider, tenant, "serving", "http://172.24.244.79:18090/v1", sealed,
                EmbeddingProvider.DataBoundary.INTERNAL, 1);
        assertFalse(stored.toString().contains(sealed));
    }
}
