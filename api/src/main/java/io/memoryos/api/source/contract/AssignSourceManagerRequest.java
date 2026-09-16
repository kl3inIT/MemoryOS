package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

@Schema(name = "AssignSourceManagerRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record AssignSourceManagerRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "Actor who may attach this source to the groups they manage; null leaves it to "
                        + "global source management alone.")
        @Nullable UUID actorId
) {}
