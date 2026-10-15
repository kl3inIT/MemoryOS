package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpToolPolicy;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "McpToolPolicyChange")
public record McpToolPolicyRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpToolPolicy policy) {}
