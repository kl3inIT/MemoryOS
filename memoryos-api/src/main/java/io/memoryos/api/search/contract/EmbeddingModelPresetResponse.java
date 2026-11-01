package io.memoryos.api.search.contract;

import io.memoryos.retrieval.settings.EmbeddingModelPreset;
import io.swagger.v3.oas.annotations.media.Schema;

/** A known model whose dimensions and prefixes the page fills in. */
public record EmbeddingModelPresetResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String model,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int dimensions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String queryPrefix,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String documentPrefix,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int maxInputTokens
) {
    public static EmbeddingModelPresetResponse from(EmbeddingModelPreset preset) {
        return new EmbeddingModelPresetResponse(preset.model(), preset.label(), preset.dimensions(), preset.queryPrefix(),
                preset.documentPrefix(), preset.maxInputTokens());
    }
}
