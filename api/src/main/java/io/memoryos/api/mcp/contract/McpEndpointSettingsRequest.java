package io.memoryos.api.mcp.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record McpEndpointSettingsRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Boolean enabled,
        @NotNull @PositiveOrZero @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long revision) {
}
