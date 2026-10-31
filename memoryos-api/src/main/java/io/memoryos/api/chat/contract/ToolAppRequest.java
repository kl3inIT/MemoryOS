package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * One request of an MCP Apps view, as assistant-ui's host sends it. {@code approved} is the person's answer to the
 * confirmation shown before a tool that asks first runs.
 */
@Schema(name = "ChatToolAppRequest")
public record ToolAppRequest(
        @Schema(requiredMode = REQUIRED, allowableValues = {"mcp-apps/read-resource", "tools/call", "resources/read", "resources/list"})
        @NotNull String method,
        @Schema(types = {"object", "null"}) @Nullable Map<String, Object> params,
        @Schema(types = {"boolean", "null"}, description = "The person approved this call of a tool that asks first")
        @Nullable Boolean approved) {}
