package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Schema(name = "UpdateSharePointScheduleRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateSharePointScheduleRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Min(1) int syncIntervalMinutes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "0 disables pruning")
        @Min(0) @Max(8760) int pruneIntervalHours) {
}
