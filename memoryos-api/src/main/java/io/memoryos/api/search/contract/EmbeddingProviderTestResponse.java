package io.memoryos.api.search.contract;

import io.memoryos.retrieval.settings.EmbeddingProbe;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** What one embedding call returned: the model named in the answer, the real dimensions and the round trip. */
public record EmbeddingProviderTestResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ok,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String model,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Integer dimensions,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long latencyMs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String error
) {
    public static EmbeddingProviderTestResponse from(EmbeddingProbe.Outcome outcome) {
        return new EmbeddingProviderTestResponse(outcome.ok(), outcome.model(), outcome.dimensions(), outcome.latencyMs(),
                outcome.error());
    }
}
