package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(name = "UpdateLarkScheduleRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateLarkScheduleRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Min(1) @Max(525600) int syncIntervalMinutes) {
}
