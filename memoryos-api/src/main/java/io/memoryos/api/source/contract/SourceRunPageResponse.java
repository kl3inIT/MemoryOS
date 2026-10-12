package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceRunHistoryService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "SourceRunPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceRunPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SourceRunResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable SourceRunResponse current,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable SourceRunResponse lastCompleted,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable SourceRunResponse lastSuccessful,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long totalItems
) {
    public static SourceRunPageResponse from(SourceRunHistoryService.Page page) {
        return new SourceRunPageResponse(page.items().stream().map(SourceRunResponse::from).toList(), page.nextCursor(),
                page.current() == null ? null : SourceRunResponse.from(page.current()),
                page.lastCompleted() == null ? null : SourceRunResponse.from(page.lastCompleted()),
                page.lastSuccessful() == null ? null : SourceRunResponse.from(page.lastSuccessful()), page.totalItems());
    }
}
