package io.memoryos.api.mcp.contract;

import io.memoryos.iam.McpClientGrant;
import io.memoryos.mcp.McpEndpointActivity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "One tool call through the MemoryOS MCP endpoint; no query and no document")
public record McpEndpointCallResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant occurredAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID actorId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String actorName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String actorEmail,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpClientGrant.Client client,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String tool,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpEndpointActivity.Outcome outcome) {
    public static McpEndpointCallResponse from(McpEndpointActivity.Call call) {
        return new McpEndpointCallResponse(call.id(), call.occurredAt(), call.actorId(), call.actorName(),
                call.actorEmail(), call.client(), call.clientName(), call.tool(), call.outcome());
    }
}
