package io.memoryos.api.source.contract;

import io.memoryos.connector.SourcePage;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "SourcePage", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourcePageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<SourceSummaryResponse> items,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        int page,
        @Schema(minimum = "1", maximum = "100", requiredMode = Schema.RequiredMode.REQUIRED)
        int size,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long totalItems,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long totalPages,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SourceCountsResponse counts
) {

    public static SourcePageResponse from(SourcePage page) {
        return new SourcePageResponse(
                page.items().stream().map(SourceSummaryResponse::from).toList(),
                page.page(),
                page.size(),
                page.totalItems(),
                page.totalPages(),
                SourceCountsResponse.from(page.counts())
        );
    }
}
