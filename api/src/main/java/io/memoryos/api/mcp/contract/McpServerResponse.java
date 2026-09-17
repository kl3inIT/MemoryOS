package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpAuthPerformer;
import io.memoryos.mcp.McpAuthType;
import io.memoryos.mcp.McpOAuthProviderMode;
import io.memoryos.mcp.McpServerService;
import io.memoryos.mcp.McpServerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "McpServerView", description = "Header values and credentials are never returned")
public record McpServerResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String slug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String url,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpAuthType authType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpAuthPerformer authPerformer,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable McpOAuthProviderMode oauthProviderMode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> oauthScopes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> oauthAdditionalParameters,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> headerNames,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean sharedCredentialConfigured,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean tenantWide,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<UUID> groupIds,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpServerStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant lastRefreshedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long toolCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long enabledToolCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision
) {
    public static McpServerResponse from(McpServerService.ServerView value) {
        return new McpServerResponse(value.id(), value.slug(), value.name(), value.description(), value.url(),
                value.authType(), value.authPerformer(), value.oauthProviderMode(), value.oauthScopes(),
                value.oauthAdditionalParameters(), value.headerNames(), value.sharedCredentialConfigured(),
                value.tenantWide(), value.groupIds(), value.status(), value.lastRefreshedAt(), value.toolCount(),
                value.enabledToolCount(), value.revision());
    }
}
