package io.memoryos.retrieval.opensearch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchPropertiesTest {
    @Test
    void rejectsEmbeddingEndpointsCarryingCredentialsQueriesFragmentsOrNoHttpHost() {
        for (String endpoint : List.of("https://user:password@provider.example/v1", "https://provider.example/v1?key=secret",
                "https://provider.example/v1#fragment", "file:///tmp/provider", "/v1", "ftp://provider.example/v1")) {
            assertThrows(IllegalArgumentException.class, () -> properties(endpoint));
        }
    }

    @Test
    void acceptsInternalHttpAsTheChatProviderPolicyDoes() {
        // docs/specs/chat-models.md#credentials-and-provider-extension: the serving node's TEI is plain HTTP on the
        // private network.
        assertDoesNotThrow(() -> properties("http://172.24.244.79:18090/v1"));
        assertDoesNotThrow(() -> properties("http://127.0.0.1:8080/v1"));
        assertDoesNotThrow(() -> properties("https://api.openai.com/v1"));
    }

    @Test
    void aSeededDeploymentNeedsNoEmbeddingEndpoint() {
        // The endpoint only seeds the first generation (MEM-216); search reports itself unavailable if one is needed.
        assertDoesNotThrow(() -> properties(""));
    }

    @Test
    void rejectsSemanticScoresOutsideCosineScoreRange() {
        for (double score : new double[] {-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> properties("https://api.openai.com/v1", score));
        }
        assertDoesNotThrow(() -> properties("https://api.openai.com/v1", 0));
        assertDoesNotThrow(() -> properties("https://api.openai.com/v1", 1));
    }

    @Test
    void anEmbeddingAttemptFitsInsideTheSearchDeadlineWithABoundedRetryCount() {
        assertDoesNotThrow(() -> properties(Duration.ofSeconds(3), 0));
        assertDoesNotThrow(() -> properties(Duration.ofSeconds(1), 3));
        assertThrows(IllegalArgumentException.class, () -> properties(Duration.ofSeconds(4), 2));
        assertThrows(IllegalArgumentException.class, () -> properties(Duration.ZERO, 2));
        assertThrows(IllegalArgumentException.class, () -> properties(Duration.ofSeconds(1), -1));
        assertThrows(IllegalArgumentException.class, () -> properties(Duration.ofSeconds(1), 4));
    }

    private static SearchProperties properties(String embeddingEndpoint) {
        return properties(embeddingEndpoint, .70);
    }

    private static SearchProperties properties(String embeddingEndpoint, double minimumSemanticScore) {
        return properties(embeddingEndpoint, minimumSemanticScore, Duration.ofSeconds(1), 2);
    }

    private static SearchProperties properties(Duration embeddingTimeout, int embeddingRetries) {
        return properties("https://api.openai.com/v1", .70, embeddingTimeout, embeddingRetries);
    }

    private static SearchProperties properties(String embeddingEndpoint, double minimumSemanticScore,
                                               Duration embeddingTimeout, int embeddingRetries) {
        return new SearchProperties(URI.create("http://127.0.0.1:9200"), "", "", "", embeddingEndpoint,
                "text-embedding-3-large", 3072, 32, 2, embeddingTimeout, embeddingRetries, 500, .5, minimumSemanticScore,
                Duration.ofSeconds(3), "memoryos-test", 0, "", "");
    }
}
