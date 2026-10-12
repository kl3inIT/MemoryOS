package io.memoryos.api.search.contract;

import io.memoryos.retrieval.settings.EmbeddingProvider;
import io.memoryos.retrieval.settings.SearchGeneration;
import io.memoryos.retrieval.settings.SearchSettingsService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One search generation and its index. {@code automatic} marks a rebuild for a new chunk convention, which switches
 * itself; {@code cleanupBlocked} marks a PAST generation whose index repeatedly failed to be deleted.
 */
public record SearchGenerationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SearchGeneration.Status status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID providerId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String providerName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EmbeddingProvider.DataBoundary dataBoundary,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String model,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int dimensions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String queryPrefix,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String documentPrefix,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double minimumSemanticScore,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String chunkConvention,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean automatic,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long documentCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant activatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant retainedUntil,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean cleanupBlocked
) {
    public static SearchGenerationResponse from(SearchSettingsService.GenerationView view) {
        var generation = view.generation();
        return new SearchGenerationResponse(generation.id(), generation.status(), generation.providerId(), view.providerName(),
                view.dataBoundary(), generation.model(), generation.dimensions(), generation.queryPrefix(),
                generation.documentPrefix(), generation.minimumSemanticScore(), generation.chunkConvention(),
                generation.automatic(), view.documentCount(), generation.createdAt(), generation.activatedAt(),
                generation.retainedUntil(), view.cleanupBlocked());
    }
}
