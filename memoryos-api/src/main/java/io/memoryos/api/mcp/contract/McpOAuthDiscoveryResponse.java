package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpOAuthService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "McpOAuthDiscovery", description = "Review only; saving a client or scopes is a separate action")
public record McpOAuthDiscoveryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String resource,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> suggestedScopes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AuthorizationServer> authorizationServers
) {
    @Schema(name = "McpOAuthAuthorizationServer")
    public record AuthorizationServer(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String issuer,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String authorizationEndpoint,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String tokenEndpoint,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String registrationEndpoint,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String revocationEndpoint,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean issParameterSupported,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean registrationAvailable,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean metadataDocumentAvailable
    ) {}

    public static McpOAuthDiscoveryResponse from(McpOAuthService.DiscoveryView value) {
        return new McpOAuthDiscoveryResponse(value.resource(), value.suggestedScopes(), value.authorizationServers().stream()
                .map(server -> new AuthorizationServer(server.issuer(), server.authorizationEndpoint(), server.tokenEndpoint(),
                        server.registrationEndpoint(), server.revocationEndpoint(), server.issParameterSupported(),
                        server.registrationAvailable(), server.metadataDocumentAvailable())).toList());
    }
}
