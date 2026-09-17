package io.memoryos.api.mcp.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "McpConnectionAuthorizationInput")
public record McpConnectionAuthorizationRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The account to connect with") UUID oauthClientId,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 512,
                description = "Relative MemoryOS page to return to, such as /chat/{sessionId}") @Nullable String returnPath
) {}
