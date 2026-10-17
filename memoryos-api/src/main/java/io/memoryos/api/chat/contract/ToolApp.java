package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatToolApp;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * The MCP Apps view of one tool call (MEM-275): the {@code ui://} resource that shows it, and the call's arguments and
 * whole {@code CallToolResult}, which the view receives.
 */
@Schema(name = "ChatToolApp")
public record ToolApp(@Schema(requiredMode = REQUIRED) String toolCallId,
                      @Schema(requiredMode = REQUIRED) String resourceUri,
                      @Schema(requiredMode = REQUIRED, type = "object") JsonNode input,
                      @Schema(requiredMode = REQUIRED, type = "object") JsonNode output) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static ToolApp from(ChatToolApp app) {
        return new ToolApp(app.callId(), app.resourceUri(), JSON.readTree(app.input()), JSON.readTree(app.output()));
    }
}
