package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceRunHistoryService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "SourceRunErrorPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceRunErrorPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SourceRunErrorResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String nextCursor
) {
    public static SourceRunErrorPageResponse from(SourceRunHistoryService.ErrorPage page) {
        return new SourceRunErrorPageResponse(page.items().stream().map(SourceRunErrorResponse::from).toList(), page.nextCursor());
    }
}
