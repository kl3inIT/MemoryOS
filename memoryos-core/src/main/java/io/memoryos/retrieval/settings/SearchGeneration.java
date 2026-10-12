package io.memoryos.retrieval.settings;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One search configuration generation and the one index it owns. Model, dimensions, document prefix and chunk
 * convention decide the vectors; they are recorded here and in the index's {@code _meta}, and the two must agree.
 * The endpoint belongs to the {@link EmbeddingProvider}, so moving the provider does not create a new index.
 */
public record SearchGeneration(UUID id, UUID tenantId, UUID providerId, String model, int dimensions,
        String queryPrefix, String documentPrefix, double minimumSemanticScore, String chunkConvention,
        String identity, Status status, boolean automatic, Instant createdAt,
        @Nullable Instant activatedAt, @Nullable Instant retainedUntil) {

    public enum Status { PRESENT, FUTURE, PAST }

    public SearchGeneration {
        Objects.requireNonNull(id); Objects.requireNonNull(tenantId); Objects.requireNonNull(providerId);
        Objects.requireNonNull(queryPrefix); Objects.requireNonNull(documentPrefix);
        Objects.requireNonNull(chunkConvention); Objects.requireNonNull(status); Objects.requireNonNull(createdAt);
        if (model == null || model.isBlank() || model.length() > 200 || dimensions < 1 || dimensions > 16000
                || queryPrefix.length() > 1000 || documentPrefix.length() > 1000
                || !Double.isFinite(minimumSemanticScore) || minimumSemanticScore < 0 || minimumSemanticScore > 1
                || identity == null || !identity.matches("[a-z][a-z0-9-]{0,159}")) {
            throw new IllegalArgumentException("invalid search generation");
        }
    }

    /** The index name of a generation created after seeding: the prefix and the generation's own ID. */
    public static String identityFor(String indexPrefix, UUID generation) {
        return indexPrefix + "-" + generation;
    }
}
