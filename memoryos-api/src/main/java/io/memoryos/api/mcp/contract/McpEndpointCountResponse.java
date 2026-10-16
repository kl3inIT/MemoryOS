package io.memoryos.api.mcp.contract;

import io.swagger.v3.oas.annotations.media.Schema;

public record McpEndpointCountResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The app's client ID or the tool's name")
        String key,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long calls,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long people) {
}
