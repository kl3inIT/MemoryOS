package io.memoryos.api.search.contract;

import io.memoryos.retrieval.settings.SearchSettingsService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** PRESENT, the FUTURE being rebuilt with its progress, and the PAST generations still restorable or blocked. */
public record SearchSettingsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SearchGenerationResponse present,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable SearchGenerationResponse future,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SearchGenerationResponse> past,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable SearchRebuildProgressResponse rebuild
) {
    public static SearchSettingsResponse from(SearchSettingsService.Settings settings) {
        var future = settings.future();
        var rebuild = settings.rebuild();
        return new SearchSettingsResponse(SearchGenerationResponse.from(settings.present()),
                future == null ? null : SearchGenerationResponse.from(future),
                settings.past().stream().map(SearchGenerationResponse::from).toList(),
                rebuild == null ? null : SearchRebuildProgressResponse.from(rebuild));
    }
}
