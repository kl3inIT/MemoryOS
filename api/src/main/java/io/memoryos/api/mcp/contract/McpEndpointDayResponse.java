package io.memoryos.api.mcp.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public record McpEndpointDayResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A UTC day") LocalDate day,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long calls,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long people) {
}
