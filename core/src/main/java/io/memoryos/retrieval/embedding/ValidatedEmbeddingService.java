package io.memoryos.retrieval.embedding;

import io.memoryos.retrieval.SearchUnavailableException;
import io.memoryos.usage.AiUsage;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageRecorder;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

/** Both ingestion and query paths use the same model space and response validation. */
public final class ValidatedEmbeddingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ValidatedEmbeddingService.class);
    private final EmbeddingModel model;
    private final String modelName;
    private final int dimensions;
    private final int batchSize;
    private final Semaphore permits;
    private final JTokkitTokenCountEstimator tokenizer = new JTokkitTokenCountEstimator();
    private final @Nullable AiUsageRecorder usage;
    private final String providerName;
    private final @Nullable Double inputPricePerMillion;

    /** Who an embedding call is for: a person's search, or system indexing without an actor. */
    public record Caller(UUID tenant, @Nullable UUID actor, AiUsageFlow flow) {}

    public ValidatedEmbeddingService(EmbeddingModel model, String modelName, int dimensions,
            int batchSize, int concurrency) {
        this(model, modelName, dimensions, batchSize, concurrency, null, "unknown", null);
    }

    /**
     * @param inputPricePerMillion USD per million input tokens of the configured embedding model; without it the calls
     *                             are recorded with unknown cost
     */
    public ValidatedEmbeddingService(EmbeddingModel model, String modelName, int dimensions, int batchSize, int concurrency,
            @Nullable AiUsageRecorder usage, String providerName, @Nullable Double inputPricePerMillion) {
        if (inputPricePerMillion != null && (!Double.isFinite(inputPricePerMillion) || inputPricePerMillion < 0))
            throw new IllegalArgumentException("invalid embedding price");
        this.usage = usage;
        this.providerName = providerName;
        this.inputPricePerMillion = inputPricePerMillion;
        this.model = Objects.requireNonNull(model);
        this.modelName = Objects.requireNonNull(modelName);
        if (dimensions < 1 || dimensions > 16000 || batchSize < 1 || batchSize > 64 || concurrency < 1 || concurrency > 16) {
            throw new IllegalArgumentException("invalid embedding bounds");
        }
        this.dimensions = dimensions;
        this.batchSize = batchSize;
        this.permits = new Semaphore(concurrency);
    }

    public int batchSize() { return batchSize; }

    public float[] query(String text) { return batch(List.of(text)).getFirst(); }

    public float[] query(String text, @Nullable Caller caller) { return batch(List.of(text), caller).getFirst(); }

    public List<float[]> batch(List<String> inputs) { return batch(inputs, null); }

    /** Embeds and, for a known caller, adds the provider-reported input tokens to the AI usage ledger. */
    public List<float[]> batch(List<String> inputs, @Nullable Caller caller) {
        inputs = List.copyOf(inputs);
        if (inputs.isEmpty() || inputs.size() > batchSize
                || inputs.stream().anyMatch(s -> s.isBlank() || tokenizer.estimate(s) > 8191)) {
            throw new IllegalArgumentException("invalid embedding input");
        }
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(5, TimeUnit.SECONDS);
            if (!acquired) throw unavailable("capacity", null);
            var response = model.call(new EmbeddingRequest(inputs,
                    EmbeddingOptions.builder().model(modelName).dimensions(dimensions).build()));
            if (response.getResults().size() != inputs.size()
                    || !modelName.equals(response.getMetadata().getModel())) {
                throw unavailable("invalid_response", null);
            }
            float[][] vectors = new float[inputs.size()][];
            for (var result : response.getResults()) {
                int position = result.getIndex();
                float[] vector = result.getOutput();
                if (position < 0 || position >= vectors.length || vectors[position] != null
                        || vector.length != dimensions) throw unavailable("invalid_response", null);
                double norm = 0;
                for (float value : vector) {
                    if (!Float.isFinite(value)) throw unavailable("invalid_response", null);
                    norm += (double) value * value;
                }
                if (norm == 0) throw unavailable("invalid_response", null);
                vectors[position] = vector.clone();
            }
            record(caller, response.getMetadata().getUsage());
            return List.copyOf(Arrays.asList(vectors));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SearchUnavailableException();
        } catch (SearchUnavailableException alreadyLogged) {
            throw alreadyLogged;
        } catch (RuntimeException failure) {
            // The OpenAI SDK exception may include request details. Keep it out of logs/API causes.
            throw unavailable("provider", failure);
        } finally {
            if (acquired) permits.release();
        }
    }

    /**
     * Names why an embedding failed, by category and exception class only: the provider's message can carry request
     * details, so it is never logged or kept as the API cause.
     */
    private SearchUnavailableException unavailable(String reason, @Nullable Throwable failure) {
        var event = LOGGER.atWarn().addKeyValue("event", "search.embedding.failed")
                .addKeyValue("reason", reason).addKeyValue("provider", providerName);
        if (failure != null) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            event = event.addKeyValue("error_type", failure.getClass().getName())
                    .addKeyValue("root_error_type", root.getClass().getName());
        }
        event.log("Embedding call failed");
        return new SearchUnavailableException();
    }

    private void record(@Nullable Caller caller, org.springframework.ai.chat.metadata.@Nullable Usage reported) {
        if (usage == null || caller == null) return;
        Integer tokens = reported == null ? null : reported.getPromptTokens();
        var at = Instant.now();
        usage.record(tokens == null || tokens <= 0
                ? AiUsage.unknown(caller.tenant(), caller.actor(), caller.flow(), providerName, modelName, null, null, null, at)
                : AiUsage.tokens(caller.tenant(), caller.actor(), caller.flow(), providerName, modelName, null, null, null,
                        tokens, 0, 0, inputPricePerMillion == null ? null : tokens * inputPricePerMillion / 1_000_000, at));
    }
}
