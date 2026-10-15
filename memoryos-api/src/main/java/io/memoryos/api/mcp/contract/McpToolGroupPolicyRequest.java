package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpToolPolicy;
import io.memoryos.mcp.McpToolRisk;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "McpToolGroupPolicyChange")
public record McpToolGroupPolicyRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The group of tools to change") McpToolRisk risk,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "DESTRUCTIVE refuses ALWAYS") McpToolPolicy policy
) {}
