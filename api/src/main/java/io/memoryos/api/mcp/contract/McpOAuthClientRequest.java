package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpOAuthService;
import io.memoryos.mcp.McpTokenEndpointAuthMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(name = "McpOAuthClientInput", description = "A pre-registered OAuth client; the secret is write-only")
public record McpOAuthClientRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100) String label,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2048) String issuer,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2048) String clientId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpSecretRequest clientSecret,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpTokenEndpointAuthMethod tokenEndpointAuthMethod,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2048) String authorizationEndpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2048) String tokenEndpoint,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 2048) @Nullable String revocationEndpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean issParameterRequired
) {
    public McpOAuthService.ClientInput toInput() {
        return new McpOAuthService.ClientInput(label, issuer, clientId, clientSecret == null ? null : clientSecret.toInput(),
                tokenEndpointAuthMethod, authorizationEndpoint, tokenEndpoint, revocationEndpoint, issParameterRequired);
    }

    @Override public @NonNull String toString() { return "McpOAuthClientRequest[redacted]"; }
}
