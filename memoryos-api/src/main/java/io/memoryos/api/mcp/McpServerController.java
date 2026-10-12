package io.memoryos.api.mcp;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.chat.contract.ChatGroupPageResponse;
import io.memoryos.api.mcp.contract.McpServerRequest;
import io.memoryos.api.mcp.contract.McpServerResponse;
import io.memoryos.api.mcp.contract.McpToolEnablementRequest;
import io.memoryos.api.mcp.contract.McpToolRefreshResponse;
import io.memoryos.api.mcp.contract.McpToolResponse;
import io.memoryos.iam.GroupQuery;
import io.memoryos.iam.IdentityContext;
import io.memoryos.mcp.McpServerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "MCP")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid MCP configuration or tool snapshot")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "MCP management, Tenant membership or CSRF requirement not met")
@ApiResponse(responseCode = "404", description = "MCP server or tool not accessible")
@ApiResponse(responseCode = "409", description = "Stale revision, duplicate slug, missing credential or server authorization required")
@ApiResponse(responseCode = "503", description = "MCP server or credential encryption key unavailable")
class McpServerController {
    private final McpServerService servers;

    McpServerController(McpServerService servers) {
        this.servers = servers;
    }

    @GetMapping("/servers")
    @ApiResponse(responseCode = "200", description = "Tenant MCP servers", useReturnTypeSchema = true)
    @Operation(operationId = "listMcpServers", summary = "List Tenant MCP servers with secrets redacted; requires MCP_MANAGE")
    List<McpServerResponse> list(@CurrentActor IdentityContext identity) {
        return servers.list(identity.actorId()).stream().map(McpServerResponse::from).toList();
    }

    @PostMapping("/servers")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createMcpServer", summary = "Register a remote Streamable HTTP MCP server; requires MCP_MANAGE")
    McpServerResponse create(@CurrentActor IdentityContext identity,
                             @RequestBody McpServerRequest request) {
        return McpServerResponse.from(servers.create(identity.actorId(), request.slug(), request.toInput()));
    }

    @GetMapping("/servers/{serverId}")
    @ApiResponse(responseCode = "200", description = "MCP server", useReturnTypeSchema = true)
    @Operation(operationId = "getMcpServer", summary = "Read one MCP server with secrets redacted; requires MCP_MANAGE")
    McpServerResponse get(@CurrentActor IdentityContext identity, @PathVariable UUID serverId) {
        return McpServerResponse.from(servers.get(identity.actorId(), serverId));
    }

    @PutMapping("/servers/{serverId}")
    @ApiResponse(responseCode = "200", description = "Saved MCP server", useReturnTypeSchema = true)
    @Operation(operationId = "updateMcpServer", summary = "Replace server settings at the expected revision; "
            + "changing URL, authentication type or performer removes stored credentials")
    McpServerResponse update(@CurrentActor IdentityContext identity,
                             @PathVariable UUID serverId, @RequestParam @Positive long revision,
                             @RequestBody McpServerRequest request) {
        return McpServerResponse.from(servers.update(identity.actorId(), serverId, revision, request.slug(), request.toInput()));
    }

    @DeleteMapping("/servers/{serverId}")
    @ApiResponse(responseCode = "204", description = "MCP server deleted with its tools and credentials", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteMcpServer", summary = "Delete an MCP server with its tools and credentials at the expected revision")
    void delete(@CurrentActor IdentityContext identity,
                @PathVariable UUID serverId, @RequestParam @Positive long revision) {
        servers.delete(identity.actorId(), serverId, revision);
    }

    @GetMapping("/group-options")
    @ApiResponse(responseCode = "200", description = "Groups available for MCP server access", useReturnTypeSchema = true)
    @Operation(operationId = "listMcpGroupOptions", summary = "List Groups available for MCP server access; requires MCP_MANAGE")
    ChatGroupPageResponse groupOptions(@CurrentActor IdentityContext identity,
            @RequestParam(required = false) @Nullable @Size(max = GroupQuery.MAX_SEARCH_LENGTH) String search,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(GroupQuery.MAX_SIZE) int size) {
        return ChatGroupPageResponse.from(servers.groupOptions(identity.actorId(), search, page, size));
    }

    @GetMapping("/servers/{serverId}/tools")
    @ApiResponse(responseCode = "200", description = "Tool snapshot", useReturnTypeSchema = true)
    @Operation(operationId = "listMcpServerTools", summary = "List the stored tool snapshot; requires MCP_MANAGE")
    List<McpToolResponse> tools(@CurrentActor IdentityContext identity, @PathVariable UUID serverId) {
        return servers.tools(identity.actorId(), serverId).stream().map(McpToolResponse::from).toList();
    }

    @PostMapping("/servers/{serverId}/tools/refresh")
    @ApiResponse(responseCode = "200", description = "Refreshed tool snapshot", useReturnTypeSchema = true)
    @Operation(operationId = "refreshMcpServerTools", summary = "Explicitly list tools from the server with the administrator credential and replace the snapshot")
    McpToolRefreshResponse refresh(@CurrentActor IdentityContext identity, @PathVariable UUID serverId) {
        return McpToolRefreshResponse.from(servers.refreshTools(identity.actorId(), serverId));
    }

    @PutMapping("/servers/{serverId}/tools/{toolId}/enabled")
    @ApiResponse(responseCode = "200", description = "Saved tool", useReturnTypeSchema = true)
    @Operation(operationId = "setMcpServerToolEnabled", summary = "Enable or disable one tool at the expected revision")
    McpToolResponse setToolEnabled(@CurrentActor IdentityContext identity,
                                   @PathVariable UUID serverId, @PathVariable UUID toolId, @RequestParam @Positive long revision,
                                   @RequestBody McpToolEnablementRequest request) {
        return McpToolResponse.from(servers.setToolEnabled(identity.actorId(), serverId, toolId, revision, request.enabled()));
    }

    @PutMapping("/servers/{serverId}/tools/enabled")
    @ApiResponse(responseCode = "200", description = "Saved tools", useReturnTypeSchema = true)
    @Operation(operationId = "setAllMcpServerToolsEnabled", summary = "Enable every exposable tool or disable every tool")
    List<McpToolResponse> setAllToolsEnabled(@CurrentActor IdentityContext identity,
                                             @PathVariable UUID serverId, @RequestBody McpToolEnablementRequest request) {
        return servers.setAllToolsEnabled(identity.actorId(), serverId, request.enabled()).stream().map(McpToolResponse::from).toList();
    }
}
