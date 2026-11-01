package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpEndpointActivity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record McpEndpointCallPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<McpEndpointCallResponse> calls,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "Pass as cursor for older calls; absent on the last page")
        @Nullable String next) {
    public static McpEndpointCallPageResponse from(McpEndpointActivity.Page page) {
        return new McpEndpointCallPageResponse(page.calls().stream().map(McpEndpointCallResponse::from).toList(),
                page.next());
    }
}
