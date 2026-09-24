package io.memoryos.api.groups.contract;

import io.memoryos.iam.GroupCapabilityMetadata;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "GroupCapabilities", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GroupCapabilitiesResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<GroupCapabilityResponse> items
) {
    public GroupCapabilitiesResponse {
        items = List.copyOf(items);
    }

    public static GroupCapabilitiesResponse from(List<GroupCapabilityMetadata> capabilities) {
        return new GroupCapabilitiesResponse(
                capabilities.stream().map(GroupCapabilityResponse::from).toList()
        );
    }
}
