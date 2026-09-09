package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceItemPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "SourceItemPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceItemPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SourceItemResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long totalItems
) {
    public static SourceItemPageResponse from(SourceItemPage page) {
        return new SourceItemPageResponse(page.items().stream().map(SourceItemResponse::from).toList(),
                page.nextCursor(), page.totalItems());
    }
}
