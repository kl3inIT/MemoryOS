package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpServerService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "McpToolRefresh")
public record McpToolRefreshResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpServerResponse server,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<McpToolResponse> tools
) {
    public static McpToolRefreshResponse from(McpServerService.Refresh value) {
        return new McpToolRefreshResponse(McpServerResponse.from(value.server()),
                value.tools().stream().map(McpToolResponse::from).toList());
    }
}
