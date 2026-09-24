package io.memoryos.retrieval.embedding;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

/** The one embedding protocol: OpenAI-compatible {@code /v1/embeddings}, which OpenAI, TEI, vLLM and Ollama speak. */
public final class OpenAiCompatibleEmbeddings {
    private OpenAiCompatibleEmbeddings() { }

    /**
     * Each attempt gets a short timeout and the SDK retries a hung or failed call (I/O errors, 408, 429, 5xx), so a
     * connection that stalls costs one attempt instead of the whole search deadline. A provider without a key yields a
     * model whose every call fails, which the validated service reports as the provider being unavailable.
     */
    public static EmbeddingModel model(String endpoint, String apiKey, String model, int dimensions, int retries,
                                       Duration timeout, ObservationRegistry observations) {
        if (apiKey.isBlank()) return new EmbeddingModel() {
            @Override public EmbeddingResponse call(EmbeddingRequest request) { throw unconfigured(); }
            @Override public float[] embed(Document document) { throw unconfigured(); }
        };
        return OpenAiEmbeddingModel.builder().metadataMode(MetadataMode.NONE).observationRegistry(observations)
                .options(OpenAiEmbeddingOptions.builder().apiKey(apiKey).baseUrl(endpoint).model(model).dimensions(dimensions)
                        .maxRetries(retries).timeout(timeout)
                        .encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT).build()).build();
    }

    private static IllegalStateException unconfigured() {
        return new IllegalStateException("embedding API key is not configured");
    }
}
