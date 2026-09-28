package io.memoryos.api.search.contract;

import io.memoryos.retrieval.settings.EmbeddingProvider;
import io.memoryos.retrieval.settings.SearchSettingsService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** An embedding provider; the key is never returned, only whether one is stored. */
public record EmbeddingProviderResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String endpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EmbeddingProvider.DataBoundary dataBoundary,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasApiKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean inUse
) {
    public static EmbeddingProviderResponse from(SearchSettingsService.ProviderView view) {
        return new EmbeddingProviderResponse(view.id(), view.name(), view.endpoint(), view.dataBoundary(), view.hasApiKey(),
                view.revision(), view.inUse());
    }
}
