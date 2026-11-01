package io.memoryos.api.chat.contract;

import io.memoryos.mcp.McpAppResource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/** An MCP Apps view's document with its {@code _meta.ui}, whose {@code csp} the renderer enforces. */
@Schema(name = "ChatToolAppResource")
public record ToolAppResource(@Schema(requiredMode = REQUIRED) String uri,
                              @Schema(requiredMode = REQUIRED, allowableValues = McpAppResource.MIME_TYPE) String mimeType,
                              @Schema(requiredMode = REQUIRED) String html,
                              @Schema(requiredMode = REQUIRED) Map<String, Object> meta) {
    public static ToolAppResource from(McpAppResource resource) {
        // An Apps SDK template is served as text/html+skybridge; the renderer accepts only the MCP Apps type.
        return new ToolAppResource(resource.uri(), McpAppResource.MIME_TYPE, resource.html(), resource.meta());
    }
}
