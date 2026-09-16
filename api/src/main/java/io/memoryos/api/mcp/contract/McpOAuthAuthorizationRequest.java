package io.memoryos.api.mcp.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "McpOAuthAuthorizationInput")
public record McpOAuthAuthorizationRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The labelled OAuth client to connect with") UUID oauthClientId
) {}
