package io.memoryos.api.mcp.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "McpToolEnablement")
public record McpToolEnablementRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled) {}
