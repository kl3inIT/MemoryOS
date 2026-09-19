package io.memoryos.api.source.contract;

import io.memoryos.connector.SharePointSourceService.SelectionPolicy;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SharePointSelectionPolicyResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SharePointSelectionPolicyResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int maxRootsPerSource,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int maxExclusionsPerKind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int maxRequestBytes) {

    public static SharePointSelectionPolicyResponse from(SelectionPolicy value) {
        return new SharePointSelectionPolicyResponse(value.maxRootsPerSource(), value.maxExclusionsPerKind(),
                value.maxRequestBytes());
    }
}
