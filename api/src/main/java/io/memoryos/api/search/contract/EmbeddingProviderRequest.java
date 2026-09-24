package io.memoryos.api.search.contract;

import io.memoryos.retrieval.settings.EmbeddingProvider;
import io.memoryos.retrieval.settings.SearchSettingsService;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * An embedding provider to create or replace. {@code apiKey} null keeps the stored key and an empty one removes it;
 * {@code revision} is the one read, required when replacing and null when creating.
 */
public record EmbeddingProviderRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String endpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String apiKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EmbeddingProvider.DataBoundary dataBoundary,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Long revision
) {
    public SearchSettingsService.ProviderInput toInput() {
        return new SearchSettingsService.ProviderInput(name, endpoint, apiKey, dataBoundary, revision);
    }

    @Override public @NonNull String toString() { return "EmbeddingProviderRequest[name=" + name + ", apiKey=REDACTED]"; }
}
