package io.memoryos.api.mcp.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "McpOAuthAuthorization")
public record McpOAuthAuthorizationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Navigate the browser here") String authorizationUrl
) {}
