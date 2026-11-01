package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpTrustedApp;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(description = "An outside assistant the MCP endpoint admits by the URL of its client metadata document")
public record McpTrustedAppResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpTrustedApp.Preset preset,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Hosts the document's URL may have")
        List<String> clientIdHosts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Hosts the URIs inside the document may have")
        List<String> documentHosts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Claude and ChatGPT can be switched off, not removed")
        boolean builtIn,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision) {
    public static McpTrustedAppResponse from(McpTrustedApp app) {
        return new McpTrustedAppResponse(app.id(), app.preset(), app.name(), app.clientIdHosts(), app.documentHosts(),
                app.enabled(), app.builtIn(), app.revision());
    }
}
