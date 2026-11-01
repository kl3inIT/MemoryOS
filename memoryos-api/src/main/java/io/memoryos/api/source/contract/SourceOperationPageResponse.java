package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceOperationPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "SourceOperationPage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceOperationPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SourceIndexAttemptResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long totalItems
) {
    public static SourceOperationPageResponse from(SourceOperationPage page) {
        return new SourceOperationPageResponse(page.items().stream().map(SourceIndexAttemptResponse::from).toList(),
                page.nextCursor(), page.totalItems());
    }
}
