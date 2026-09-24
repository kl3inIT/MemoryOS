package io.memoryos.api.search.contract;

import io.memoryos.retrieval.settings.SearchSettingsService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** The model to rebuild the index with; prefixes are sent verbatim because trailing spaces are part of them. */
public record SearchGenerationRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID providerId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String model,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int dimensions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String queryPrefix,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String documentPrefix,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double minimumSemanticScore
) {
    public SearchSettingsService.GenerationInput toInput() {
        return new SearchSettingsService.GenerationInput(providerId, model, dimensions, queryPrefix, documentPrefix,
                minimumSemanticScore);
    }
}
