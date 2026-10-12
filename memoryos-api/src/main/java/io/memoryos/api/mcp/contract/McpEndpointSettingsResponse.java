package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpEndpointService;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record McpEndpointSettingsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether this deployment configured an MCP endpoint URL; without one the switch stays off")
        boolean configured,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "The URL people add to Claude or ChatGPT; present when configured")
        @Nullable String url) {
    public static McpEndpointSettingsResponse from(McpEndpointService.Settings settings) {
        return new McpEndpointSettingsResponse(settings.configured(), settings.enabled(), settings.revision(),
                settings.url() == null ? null : settings.url().toString());
    }
}
