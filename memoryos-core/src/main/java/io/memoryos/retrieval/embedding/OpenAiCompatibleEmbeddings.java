package io.memoryos.retrieval.embedding;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

/** The one embedding protocol: OpenAI-compatible {@code /v1/embeddings}, which OpenAI, TEI, vLLM and Ollama speak. */
@NullMarked
public final class OpenAiCompatibleEmbeddings {
    /**
     * Sent as the bearer token to a provider saved without a key (Ollama, LM Studio, an internal TEI without
     * {@code --api-key}, or the serving provider seeded before its key is entered); the SDK requires one.
     */
    public static final String NO_KEY = "no-key";

    private OpenAiCompatibleEmbeddings() { }

    /**
     * Each attempt gets a short timeout and the SDK retries a hung or failed call (I/O errors, 408, 429, 5xx), so a
     * connection that stalls costs one attempt instead of the whole search deadline. A provider that refuses the key
     * fails every call, which the validated service reports as the provider being unavailable.
     */
    public static EmbeddingModel model(String endpoint, String apiKey, String model, @Nullable Integer dimensions, int retries,
                                       Duration timeout, ObservationRegistry observations) {
        return OpenAiEmbeddingModel.builder().metadataMode(MetadataMode.NONE).observationRegistry(observations)
                .options(OpenAiEmbeddingOptions.builder().apiKey(apiKey).baseUrl(endpoint).model(model).dimensions(dimensions)
                        .maxRetries(retries).timeout(timeout)
                        .encodingFormat(OpenAiEmbeddingOptions.EncodingFormat.FLOAT).build()).build();
    }
}
