package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(name = "UpdateGoogleDrivePauseRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateGoogleDrivePauseRequest(
        @NotNull @Min(1) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") Long expectedRevision,
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Boolean paused) {}
