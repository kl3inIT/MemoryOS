package io.memoryos.api.search.contract;

import io.memoryos.retrieval.settings.SearchSettingsService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A saved provider ({@code providerId}, with an edited endpoint or key where given) or an unsaved endpoint and key;
 * {@code dimensions} null lets the endpoint answer with its own.
 */
public record EmbeddingProviderTestRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable UUID providerId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String endpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String apiKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String model,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Integer dimensions
) {
    public SearchSettingsService.TestInput toInput() {
        return new SearchSettingsService.TestInput(providerId, endpoint, apiKey, model, dimensions);
    }

    @Override public @NonNull String toString() { return "EmbeddingProviderTestRequest[providerId=" + providerId + ", apiKey=REDACTED]"; }
}
