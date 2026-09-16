package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpAuthPerformer;
import io.memoryos.mcp.McpAuthType;
import io.memoryos.mcp.McpConnectionService;
import io.memoryos.mcp.McpConnectionState;
import io.memoryos.mcp.McpServerStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "McpConnection", description = "An MCP server the signed-in User may use, with their own connection state")
public record McpConnectionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String slug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String url,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpAuthType authType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpAuthPerformer authPerformer,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpServerStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpConnectionState connectionState,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Accounts to choose between when connecting")
        List<McpConnectionClientResponse> oauthClients,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long enabledToolCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant connectedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision
) {
    @Schema(name = "McpConnectionClient", description = "A labelled OAuth client; endpoints stay server-side")
    public record McpConnectionClientResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label) {}

    public static McpConnectionResponse from(McpConnectionService.ConnectionView view) {
        return new McpConnectionResponse(view.id(), view.slug(), view.name(), view.description(), view.url(),
                view.authType(), view.authPerformer(), view.status(), view.state(),
                view.oauthClients().stream().map(client -> new McpConnectionClientResponse(client.id(), client.label())).toList(),
                view.enabledToolCount(), view.connectedAt(), view.revision());
    }
}
