package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpAuthPerformer;
import io.memoryos.mcp.McpAuthType;
import io.memoryos.mcp.McpOAuthProviderMode;
import io.memoryos.mcp.McpServerService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(name = "McpServerInput")
public record McpServerRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[a-z0-9]{1,16}$",
                description = "Immutable; model-facing tool names are mcp_<slug>_<tool>") String slug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200) String name,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 2000) @Nullable String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2048, description = "Streamable HTTP endpoint") String url,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpAuthType authType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpAuthPerformer authPerformer,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) @Nullable McpOAuthProviderMode oauthProviderMode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> oauthScopes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> oauthAdditionalParameters,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpHeaderTemplateRequest headers,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpSecretRequest sharedApiKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean tenantWide,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<UUID> groupIds
) {
    public McpServerService.ServerInput toInput() {
        return new McpServerService.ServerInput(name, description, url, authType, authPerformer, oauthProviderMode,
                oauthScopes, oauthAdditionalParameters, headers == null ? null : headers.toInput(),
                sharedApiKey == null ? null : sharedApiKey.toInput(), tenantWide, groupIds);
    }

    @Override public @NonNull String toString() { return "McpServerRequest[redacted]"; }
}
