package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpTrustedAppService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record McpTrustedAppListResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether this deployment can change the list; without its Keycloak account it is read-only")
        boolean manageable,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<McpTrustedAppResponse> apps) {
    public static McpTrustedAppListResponse from(McpTrustedAppService.Listing listing) {
        return new McpTrustedAppListResponse(listing.manageable(),
                listing.apps().stream().map(McpTrustedAppResponse::from).toList());
    }
}
