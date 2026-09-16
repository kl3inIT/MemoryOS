package io.memoryos.api.mcp.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

@Schema(name = "McpConnectionApiKeyInput", description = "The User's own API key; write-only")
public record McpConnectionApiKeyRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 8192) String apiKey
) {
    @Override public @NonNull String toString() { return "McpConnectionApiKeyRequest[redacted]"; }
}
