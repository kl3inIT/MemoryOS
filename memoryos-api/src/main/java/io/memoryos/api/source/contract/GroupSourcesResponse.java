package io.memoryos.api.source.contract;

import io.memoryos.connector.GroupSources;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "GroupSources", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GroupSourcesResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<SourceSummaryResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Sources in items that the caller may remove from this group.")
        List<UUID> removableSourceIds
) {
    public GroupSourcesResponse {
        items = List.copyOf(items);
        removableSourceIds = List.copyOf(removableSourceIds);
    }

    public static GroupSourcesResponse from(GroupSources groupSources) {
        return new GroupSourcesResponse(
                groupSources.sources().stream().map(SourceSummaryResponse::from).toList(),
                groupSources.sources().stream()
                        .filter(source -> groupSources.removableSourceIds().contains(source.id()))
                        .map(source -> source.id().value())
                        .toList()
        );
    }
}
