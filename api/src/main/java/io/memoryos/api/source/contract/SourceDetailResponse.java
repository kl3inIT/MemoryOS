package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceDetail;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SourceDetail", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceDetailResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SourceSummaryResponse source,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<SourceItemResponse> items
) {
    public SourceDetailResponse {
        items = List.copyOf(items);
    }

    public static SourceDetailResponse from(SourceDetail detail) {
        return new SourceDetailResponse(
                SourceSummaryResponse.from(detail.source()),
                detail.items().stream().map(SourceItemResponse::from).toList()
        );
    }
}
