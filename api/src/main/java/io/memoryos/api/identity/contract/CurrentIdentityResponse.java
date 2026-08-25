package io.memoryos.api.identity.contract;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(name = "CurrentIdentity", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CurrentIdentityResponse(
        @Schema(
                description = "Stable internal MemoryOS actor identifier.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        UUID actorId
) {
}
