package io.memoryos.retrieval.settings;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * An OpenAI-compatible {@code /v1/embeddings} endpoint (OpenAI, TEI, vLLM, Ollama, LM Studio). The stored credential
 * is ciphertext or the deployment reference, never a key; see {@link EmbeddingProviderCredentials}.
 */
public record EmbeddingProvider(UUID id, UUID tenantId, String name, String endpoint, @Nullable String credential,
        DataBoundary dataBoundary, long revision) {

    /** Display only until the outbound data gate (MEM-134) exists. */
    public enum DataBoundary { INTERNAL, EXTERNAL }

    public EmbeddingProvider {
        Objects.requireNonNull(id); Objects.requireNonNull(tenantId); Objects.requireNonNull(dataBoundary);
        if (name == null || name.isBlank() || name.length() > 200 || revision < 1) {
            throw new IllegalArgumentException("invalid embedding provider");
        }
        validateEndpoint(endpoint);
    }

    /**
     * The Chat provider rule (docs/specs/chat-models.md#credentials-and-provider-extension): HTTP(S) to any host,
     * internal hosts included, because a model manager is trusted to choose where a key goes; URL credentials, query
     * strings and fragments are refused.
     */
    public static void validateEndpoint(String endpoint) {
        URI uri;
        try { uri = new URI(Objects.requireNonNull(endpoint)); }
        catch (Exception invalid) { throw new IllegalArgumentException("invalid embedding endpoint"); }
        if (endpoint.length() > 2048 || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("invalid embedding endpoint");
        }
    }

    @Override public @NonNull String toString() {
        return "EmbeddingProvider[id=" + id + ", name=" + name + ", endpoint=" + endpoint + ", credential=REDACTED]";
    }
}
