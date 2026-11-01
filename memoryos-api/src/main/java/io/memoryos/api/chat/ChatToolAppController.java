package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ToolAppRequest;
import io.memoryos.api.chat.contract.ToolAppResource;
import io.memoryos.api.security.CurrentActor;
import io.memoryos.chat.ChatToolAppService;
import io.memoryos.iam.IdentityContext;
import io.memoryos.mcp.McpException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The host route of the MCP Apps views in a conversation's answers (MEM-275), as assistant-ui's remote host calls it.
 * Each request runs on the server whose tool showed the view; the conversation's owner is the only caller.
 */
@RestController
@RequestMapping(value = "/api/chat/sessions/{sessionId}/messages/{messageId}/apps/{callId}",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid view request")
@ApiResponse(responseCode = "403", description = "Tenant membership, CSRF requirement or tool policy not met")
@ApiResponse(responseCode = "404", description = "View not accessible")
@ApiResponse(responseCode = "409", description = "The tool asks first, or the server must be connected again")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatToolAppController {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ChatToolAppService apps;

    ChatToolAppController(ChatToolAppService apps) {
        this.apps = apps;
    }

    @PostMapping
    @Operation(operationId = "hostChatToolApp", summary = "Run one request of an MCP Apps view in an owned answer")
    @ApiResponse(responseCode = "200", description = "The MCP result, or the view's document for mcp-apps/read-resource",
            content = @Content(schema = @Schema(type = "object")))
    Object host(@CurrentActor IdentityContext identity, @PathVariable UUID sessionId, @PathVariable UUID messageId,
                @PathVariable String callId, @Valid @RequestBody ToolAppRequest request) {
        var actor = identity.actorId();
        Map<String, Object> params = request.params() == null ? Map.of() : request.params();
        return switch (request.method()) {
            case "mcp-apps/read-resource" ->
                    ToolAppResource.from(apps.view(actor, sessionId, messageId, callId, text(params, "uri")));
            case "tools/call" -> json(apps.callTool(actor, sessionId, messageId, callId, text(params, "name"),
                    arguments(params.get("arguments")), Boolean.TRUE.equals(request.approved())));
            case "resources/read" -> json(apps.readResource(actor, sessionId, messageId, callId, text(params, "uri")));
            case "resources/list" -> json(apps.listResources(actor, sessionId, messageId, callId,
                    params.get("cursor") instanceof String cursor ? cursor : null));
            default -> throw McpException.invalid("The view asked for an unsupported method.");
        };
    }

    private static String text(Map<String, Object> params, String name) {
        if (params.get(name) instanceof String value && !value.isBlank()) return value;
        throw McpException.invalid("The view's request names no " + name + ".");
    }

    private static Map<String, Object> arguments(@Nullable Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?>)) throw McpException.invalid("Tool arguments must be a JSON object.");
        return JSON.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }

    private static JsonNode json(String value) {
        return JSON.readTree(value);
    }
}
