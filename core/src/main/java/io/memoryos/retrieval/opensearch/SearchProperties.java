package io.memoryos.retrieval.opensearch;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("memoryos.search")
public record SearchProperties(
        @DefaultValue("http://127.0.0.1:9200") URI endpoint,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("") String caCertificate,
        @DefaultValue("https://api.openai.com/v1") String embeddingEndpoint,
        @DefaultValue("") String apiKey,
        @DefaultValue("text-embedding-3-large") String model,
        @DefaultValue("3072") int dimensions,
        @DefaultValue("32") int embeddingBatchSize,
        @DefaultValue("2") int embeddingConcurrency,
        @DefaultValue("10s") Duration embeddingTimeout,
        @DefaultValue("2") int embeddingRetries,
        @DefaultValue("500") int candidateLimit,
        @DefaultValue("0.5") double keywordWeight,
        @DefaultValue("0.70") double minimumSemanticScore,
        @DefaultValue("30s") Duration timeout,
        @DefaultValue("memoryos-chunks") String indexPrefix,
        @DefaultValue("1") int replicas,
        @DefaultValue("") String queryPrefix,
        @DefaultValue("") String documentPrefix) {
    public SearchProperties {
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || !endpoint.getPath().isEmpty() && !endpoint.getPath().equals("/")) throw new IllegalArgumentException("invalid search endpoint");
        boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost());
        if (!"https".equals(endpoint.getScheme()) && !(loopback && "http".equals(endpoint.getScheme()) && username.isEmpty() && password.isEmpty())) {
            throw new IllegalArgumentException("OpenSearch credentials and remote endpoints require HTTPS");
        }
        if (username.isBlank() != password.isBlank()) throw new IllegalArgumentException("incomplete search credentials");
        // The Chat provider endpoint rule (docs/specs/chat-models.md#credentials-and-provider-extension): HTTP is
        // allowed with a key, internal hosts included; credentials, query strings and fragments in the URL are not.
        var embeddingUri = URI.create(embeddingEndpoint);
        if (embeddingUri.getHost() == null || embeddingUri.getRawUserInfo() != null
                || embeddingUri.getRawQuery() != null || embeddingUri.getRawFragment() != null
                || !Set.of("http", "https").contains(embeddingUri.getScheme())) {
            throw new IllegalArgumentException("invalid embedding endpoint");
        }
        if (queryPrefix.length() > 1000 || documentPrefix.length() > 1000) throw new IllegalArgumentException("invalid embedding prefix");
        if (!indexPrefix.matches("[a-z][a-z0-9-]{0,59}") || !Double.isFinite(keywordWeight)
                || keywordWeight <= 0 || keywordWeight >= 1 || candidateLimit < 50 || candidateLimit > 1000
                || !Double.isFinite(minimumSemanticScore) || minimumSemanticScore < 0 || minimumSemanticScore > 1
                || dimensions < 1 || dimensions > 16000 || replicas < 0 || replicas > 3
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(1)) > 0
                || embeddingTimeout.isNegative() || embeddingTimeout.isZero() || embeddingTimeout.compareTo(timeout) > 0
                || embeddingRetries < 0 || embeddingRetries > 3) {
            throw new IllegalArgumentException("invalid search configuration");
        }
    }

    @Override public @NonNull String toString() { return "SearchProperties[model=" + model + ", dimensions=" + dimensions + ", credentials=REDACTED]"; }
}
