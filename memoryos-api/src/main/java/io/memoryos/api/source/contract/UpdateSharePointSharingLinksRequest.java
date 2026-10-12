package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UpdateSharePointSharingLinksRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateSharePointSharingLinksRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean treatAsPublic) {
}
