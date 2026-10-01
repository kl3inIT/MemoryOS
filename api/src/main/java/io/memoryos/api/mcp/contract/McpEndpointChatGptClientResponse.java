package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpEndpointService;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What a ChatGPT workspace administrator enters once to add MemoryOS")
public record McpEndpointChatGptClientResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String clientSecret) {
    public static McpEndpointChatGptClientResponse from(McpEndpointService.ChatGptClient client) {
        return new McpEndpointChatGptClientResponse(client.clientId(), client.clientSecret());
    }

    @Override
    public String toString() {
        return "McpEndpointChatGptClientResponse[clientId=" + clientId + ", clientSecret=<redacted>]";
    }
}
