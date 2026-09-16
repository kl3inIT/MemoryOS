package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpSecretAction;
import io.memoryos.mcp.McpServerService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(name = "McpHeaderTemplateChange", description = "Header values may contain secrets and are never returned; "
        + "API-key servers place {api_key}")
public record McpHeaderTemplateRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpSecretAction action,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) @Nullable Map<String, String> values
) {
    public McpServerService.HeaderChange toInput() { return new McpServerService.HeaderChange(action, values); }

    @Override public @NonNull String toString() { return "McpHeaderTemplateRequest[redacted]"; }
}
