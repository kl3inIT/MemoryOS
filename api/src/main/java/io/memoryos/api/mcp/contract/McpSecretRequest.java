package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpSecretAction;
import io.memoryos.mcp.McpServerService;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(name = "McpSecretChange")
public record McpSecretRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpSecretAction action,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 8192) @Nullable String value
) {
    public McpServerService.SecretChange toInput() { return new McpServerService.SecretChange(action, value); }

    @Override public @NonNull String toString() { return "McpSecretRequest[redacted]"; }
}
