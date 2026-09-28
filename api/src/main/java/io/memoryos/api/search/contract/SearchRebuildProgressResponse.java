package io.memoryos.api.search.contract;

import io.memoryos.retrieval.settings.SearchSettingsService;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Documents over the servable corpus; {@code switchable} once nothing is pending and PRESENT's documents are covered. */
public record SearchRebuildProgressResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long ready,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long total,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long failed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long pending,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Long estimatedSecondsRemaining,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean switchable
) {
    public static SearchRebuildProgressResponse from(SearchSettingsService.RebuildProgress progress) {
        return new SearchRebuildProgressResponse(progress.ready(), progress.total(), progress.failed(), progress.pending(),
                progress.estimatedSecondsRemaining(), progress.switchable());
    }
}
