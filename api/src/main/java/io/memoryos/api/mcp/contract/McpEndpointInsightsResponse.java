package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpEndpointActivity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Use of the MemoryOS MCP endpoint over the last 7 or 30 UTC days")
public record McpEndpointInsightsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int days,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long calls,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Distinct people who called a tool")
        long people,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long failed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long rateLimited,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<McpEndpointCountResponse> apps,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<McpEndpointCountResponse> tools,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Days with at least one call")
        List<McpEndpointDayResponse> daily) {
    public static McpEndpointInsightsResponse from(McpEndpointActivity.Insights insights) {
        return new McpEndpointInsightsResponse(insights.days(), insights.calls(), insights.people(), insights.failed(),
                insights.rateLimited(), insights.apps().stream().map(McpEndpointInsightsResponse::count).toList(),
                insights.tools().stream().map(McpEndpointInsightsResponse::count).toList(),
                insights.daily().stream().map(day -> new McpEndpointDayResponse(day.day(), day.calls(), day.people()))
                        .toList());
    }

    private static McpEndpointCountResponse count(McpEndpointActivity.Count count) {
        return new McpEndpointCountResponse(count.key(), count.name(), count.calls(), count.people());
    }
}
