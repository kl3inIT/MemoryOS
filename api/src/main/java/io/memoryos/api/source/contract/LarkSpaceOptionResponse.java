package io.memoryos.api.source.contract;

import io.memoryos.connector.LarkSourceService.SpaceOption;
import io.swagger.v3.oas.annotations.media.Schema;

/** A Wiki space the credential's app is a member of, which a Source may name. */
@Schema(name = "LarkSpaceOptionResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LarkSpaceOptionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String spaceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {

    public static LarkSpaceOptionResponse from(SpaceOption value) {
        return new LarkSpaceOptionResponse(value.spaceId(), value.name());
    }
}
