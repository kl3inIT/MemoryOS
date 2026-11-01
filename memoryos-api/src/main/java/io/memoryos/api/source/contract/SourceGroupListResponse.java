package io.memoryos.api.source.contract;

import io.memoryos.iam.GroupIdentity;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SourceGroupList", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceGroupListResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<SourceGroupResponse> items
) {
    public SourceGroupListResponse {
        items = List.copyOf(items);
    }

    public static SourceGroupListResponse from(List<GroupIdentity> groups) {
        return new SourceGroupListResponse(groups.stream().map(SourceGroupResponse::from).toList());
    }
}
