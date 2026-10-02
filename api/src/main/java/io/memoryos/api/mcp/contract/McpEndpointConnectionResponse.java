package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpEndpointService;
import io.memoryos.mcp.McpTrustedApp;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record McpEndpointConnectionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether Claude and ChatGPT can reach the MemoryOS MCP endpoint now")
        boolean available,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "The URL to add to Claude or ChatGPT; present while available")
        @Nullable String url,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The apps the administrator trusts; CUSTOM when any app of the organization's own is")
        List<McpTrustedApp.Preset> apps) {
    public static McpEndpointConnectionResponse from(McpEndpointService.Connection connection) {
        return new McpEndpointConnectionResponse(connection.available(),
                connection.url() == null ? null : connection.url().toString(),
                connection.apps().stream().sorted().toList());
    }
}
