package io.memoryos.api.mcp.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "McpToolApproval")
public record McpToolApprovalRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean approvalRequired) {}
