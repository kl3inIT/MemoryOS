package io.memoryos.retrieval.embedding;

import io.memoryos.retrieval.SearchUnavailableException;
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
    private final EmbeddingModel model;
    private final String modelName;
    private final int dimensions;
    private final int batchSize;
    private final Semaphore permits;
    private final JTokkitTokenCountEstimator tokenizer = new JTokkitTokenCountEstimator();

    public ValidatedEmbeddingService(EmbeddingModel model, String modelName, int dimensions,
            int batchSize, int concurrency) {
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

    public List<float[]> batch(List<String> inputs) {
        inputs = List.copyOf(inputs);
        if (inputs.isEmpty() || inputs.size() > batchSize
                || inputs.stream().anyMatch(s -> s.isBlank() || tokenizer.estimate(s) > 8191)) {
            throw new IllegalArgumentException("invalid embedding input");
        }
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(5, TimeUnit.SECONDS);
            if (!acquired) throw new SearchUnavailableException();
            var response = model.call(new EmbeddingRequest(inputs,
                    EmbeddingOptions.builder().model(modelName).dimensions(dimensions).build()));
            if (response.getResults().size() != inputs.size()
                    || !modelName.equals(response.getMetadata().getModel())) {
                throw new SearchUnavailableException();
            }
            float[][] vectors = new float[inputs.size()][];
            for (var result : response.getResults()) {
                int position = result.getIndex();
                float[] vector = result.getOutput();
                if (position < 0 || position >= vectors.length || vectors[position] != null
                        || vector.length != dimensions) throw new SearchUnavailableException();
                double norm = 0;
                for (float value : vector) {
                    if (!Float.isFinite(value)) throw new SearchUnavailableException();
                    norm += (double) value * value;
                }
                if (norm == 0) throw new SearchUnavailableException();
                vectors[position] = vector.clone();
            }
            return List.copyOf(Arrays.asList(vectors));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SearchUnavailableException();
        } catch (RuntimeException failure) {
            // The OpenAI SDK exception may include request details. Keep it out of logs/API causes.
            throw new SearchUnavailableException();
        } finally {
            if (acquired) permits.release();
        }
    }
}
