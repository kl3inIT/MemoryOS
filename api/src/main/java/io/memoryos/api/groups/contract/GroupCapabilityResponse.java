package io.memoryos.api.groups.contract;

import io.memoryos.iam.GroupCapabilityMetadata;
import io.memoryos.iam.IamCapability;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Comparator;
import java.util.List;

@Schema(name = "GroupCapability", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GroupCapabilityResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        IamCapability id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String label,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean editable,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<IamCapability> implies
) {
    public GroupCapabilityResponse {
        implies = List.copyOf(implies);
    }

    public static GroupCapabilityResponse from(GroupCapabilityMetadata capability) {
        return new GroupCapabilityResponse(
                capability.id(),
                capability.label(),
                capability.description(),
                capability.editable(),
                capability.implies().stream()
                        .sorted(Comparator.comparingInt(IamCapability::ordinal))
                        .toList()
        );
    }
}
