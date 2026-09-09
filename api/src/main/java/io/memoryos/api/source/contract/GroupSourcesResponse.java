package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceSummary;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "GroupSources", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GroupSourcesResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<SourceSummaryResponse> items
) {
    public GroupSourcesResponse {
        items = List.copyOf(items);
    }

    public static GroupSourcesResponse from(List<SourceSummary> sources) {
        return new GroupSourcesResponse(sources.stream().map(SourceSummaryResponse::from).toList());
    }
}
