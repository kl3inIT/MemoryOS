package io.memoryos.retrieval.opensearch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchPropertiesTest {
    @Test
    void rejectsEmbeddingCredentialsOverHttpAndMalformedEndpoints() {
        for (String endpoint : List.of("http://127.0.0.1:8080/v1", "http://provider.example/v1",
                "https://user:password@provider.example/v1", "file:///tmp/provider", "/v1")) {
            assertThrows(IllegalArgumentException.class, () -> properties(endpoint, "test-only-credential"));
        }
    }

    @Test
    void acceptsHttpsCredentialsAndEmptyKeyLocalConfiguration() {
        assertDoesNotThrow(() -> properties("https://api.openai.com/v1", "test-only-credential"));
        assertDoesNotThrow(() -> properties("http://127.0.0.1:8080/v1", ""));
    }

    @Test
    void rejectsSemanticScoresOutsideCosineScoreRange() {
        for (double score : new double[] {-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> properties("https://api.openai.com/v1", "test-only-credential", score));
        }
        assertDoesNotThrow(() -> properties("https://api.openai.com/v1", "test-only-credential", 0));
        assertDoesNotThrow(() -> properties("https://api.openai.com/v1", "test-only-credential", 1));
    }

    private static SearchProperties properties(String embeddingEndpoint, String apiKey) {
        return properties(embeddingEndpoint, apiKey, .70);
    }

    private static SearchProperties properties(String embeddingEndpoint, String apiKey, double minimumSemanticScore) {
        return new SearchProperties(URI.create("http://127.0.0.1:9200"), "", "", "", embeddingEndpoint, apiKey,
                "text-embedding-3-large", 3072, 32, 2, 500, .5, minimumSemanticScore,
                Duration.ofSeconds(3), "memoryos-test", 0);
    }
}
