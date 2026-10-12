package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

@Schema(name = "UpdateLarkPauseRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateLarkPauseRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Positive long expectedRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean paused) {
}
