package io.memoryos.retrieval.settings;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import io.memoryos.retrieval.embedding.OpenAiCompatibleEmbeddings;
import io.memoryos.retrieval.opensearch.SearchProperties;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.stereotype.Component;

/**
 * One real {@code /v1/embeddings} call, as the connection check and the start of a rebuild make. The outcome names
 * what went wrong in our own words: the provider's message can carry request details, so it is neither returned nor
 * logged.
 */
@Component
public class EmbeddingProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingProbe.class);
    private final SearchProperties properties;
    private final ObservationRegistry observations;

    public EmbeddingProbe(SearchProperties properties, ObservationRegistry observations) {
        this.properties = properties;
        this.observations = observations;
    }

    /** Why a call failed: the endpoint refused it, could not be reached, or answered with other vectors than asked. */
    public enum Failure { REJECTED, UNREACHABLE, MISMATCH }

    public record Outcome(boolean ok, @Nullable String model, @Nullable Integer dimensions, long latencyMs,
            @Nullable Failure failure, @Nullable String error) { }

    public Outcome probe(String endpoint, String key, String model, @Nullable Integer dimensions) {
        long started = System.nanoTime();
        try {
            var client = OpenAiCompatibleEmbeddings.model(endpoint, key, model, dimensions, 0,
                    properties.embeddingTimeout(), observations);
            var response = client.call(new EmbeddingRequest(List.of("MemoryOS"),
                    EmbeddingOptions.builder().model(model).dimensions(dimensions).build()));
            long latency = elapsed(started);
            if (response.getResults().isEmpty()) {
                return failed(Failure.MISMATCH, latency, null, null, "The endpoint returned no vector.");
            }
            String returned = response.getMetadata().getModel();
            int length = response.getResults().getFirst().getOutput().length;
            if (returned == null || !returned.equals(model)) {
                return failed(Failure.MISMATCH, latency, returned, length, "The endpoint answered for another model name.");
            }
            if (dimensions != null && length != dimensions) {
                return failed(Failure.MISMATCH, latency, returned, length,
                        "The endpoint returned " + length + " dimensions instead of " + dimensions + ".");
            }
            return new Outcome(true, returned, length, latency, null, null);
        } catch (RuntimeException failure) {
            long latency = elapsed(started);
            Integer status = status(failure);
            LOGGER.atWarn().addKeyValue("event", "search.embedding.probe_failed")
                    .addKeyValue("status", status).addKeyValue("error_type", failure.getClass().getName())
                    .log("Embedding connection check failed");
            if (status != null && (status == 401 || status == 403)) {
                return failed(Failure.REJECTED, latency, null, null, "The endpoint rejected the API key.");
            }
            if (status != null && status >= 400 && status < 500) {
                return failed(Failure.REJECTED, latency, null, null,
                        "The endpoint refused the request (HTTP " + status + "). Check the model name and dimensions.");
            }
            if (status != null) {
                return failed(Failure.UNREACHABLE, latency, null, null, "The endpoint failed (HTTP " + status + ").");
            }
            return failed(Failure.UNREACHABLE, latency, null, null, "The endpoint could not be reached.");
        }
    }

    private static @Nullable Integer status(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof OpenAIServiceException service) return service.statusCode();
            if (cause instanceof OpenAIIoException) return null;
        }
        return null;
    }

    private static Outcome failed(Failure failure, long latency, @Nullable String model, @Nullable Integer dimensions, String error) {
        return new Outcome(false, model, dimensions, latency, failure, error);
    }

    private static long elapsed(long started) { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }
}
