package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpServerService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "McpToolView", description = "Annotation hints come from the server and are not enforced guarantees")
public record McpToolResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Empty when the tool is not exposable") String modelName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Boolean readOnlyHint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Boolean destructiveHint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean exposable,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant snapshotAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision
) {
    public static McpToolResponse from(McpServerService.ToolView value) {
        return new McpToolResponse(value.id(), value.name(), value.modelName(), value.title(), value.description(),
                value.readOnlyHint(), value.destructiveHint(), value.enabled(), value.exposable(), value.snapshotAt(),
                value.revision());
    }
}
