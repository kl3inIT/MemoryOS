package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpOAuthClientSource;
import io.memoryos.mcp.McpOAuthService;
import io.memoryos.mcp.McpTokenEndpointAuthMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "McpOAuthClientView", description = "Client secrets and registration tokens are never returned")
public record McpOAuthClientResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpOAuthClientSource source,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String issuer,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean clientSecretConfigured,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpTokenEndpointAuthMethod tokenEndpointAuthMethod,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String authorizationEndpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String tokenEndpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String revocationEndpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean issParameterRequired,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision
) {
    public static McpOAuthClientResponse from(McpOAuthService.ClientView value) {
        return new McpOAuthClientResponse(value.id(), value.label(), value.source(), value.issuer(), value.clientId(),
                value.clientSecretConfigured(), value.tokenEndpointAuthMethod(), value.authorizationEndpoint(),
                value.tokenEndpoint(), value.revocationEndpoint(), value.issParameterRequired(), value.revision());
    }
}
