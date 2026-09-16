package io.memoryos.api.mcp;

import io.memoryos.api.mcp.contract.McpConnectionApiKeyRequest;
import io.memoryos.api.mcp.contract.McpConnectionAuthorizationRequest;
import io.memoryos.api.mcp.contract.McpConnectionResponse;
import io.memoryos.api.mcp.contract.McpOAuthAuthorizationResponse;
import io.memoryos.iam.identity.IdentityContext;
import io.memoryos.mcp.McpConnectionService;
import io.memoryos.mcp.McpOAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in User's own MCP connections; administration lives in {@code /api/mcp/servers}. */
@RestController
@RequestMapping(value = "/api/mcp/connections", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "MCP")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Unusable API key, return address or server configuration", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Chat use, Tenant membership or CSRF requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "MCP server not available to this User", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "409", description = "Server configuration changed, or the credential was rejected", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "503", description = "MCP server or credential encryption key unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
class McpConnectionController {
    private final McpConnectionService connections;
    private final McpOAuthService oauth;

    McpConnectionController(McpConnectionService connections, McpOAuthService oauth) {
        this.connections = connections;
        this.oauth = oauth;
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "MCP servers this User may use", useReturnTypeSchema = true)
    @Operation(operationId = "listMcpConnections", summary = "List the MCP servers available to the signed-in User with their own connection state")
    List<McpConnectionResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return connections.list(identity.actorId()).stream().map(McpConnectionResponse::from).toList();
    }

    @PostMapping("/{serverId}/authorization")
    @ApiResponse(responseCode = "200", description = "Authorization URL for the browser", useReturnTypeSchema = true)
    @Operation(operationId = "startMcpConnectionAuthorization", summary = "Start the User's own OAuth connection and return to the originating page")
    ResponseEntity<McpOAuthAuthorizationResponse> authorize(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                                            @PathVariable UUID serverId,
                                                            @RequestBody McpConnectionAuthorizationRequest request,
                                                            HttpServletRequest servletRequest) {
        McpAuthorizationSessionState.requireSession(servletRequest, identity);
        String returnPath = McpReturnPath.validate(request.returnPath());
        String state = McpAuthorizationSessionState.random();
        String verifier = McpAuthorizationSessionState.random();
        var start = oauth.startUserAuthorization(identity.actorId(), serverId, request.oauthClientId(), state,
                McpAuthorizationSessionState.challenge(verifier), returnPath);
        McpAuthorizationSessionState.store(servletRequest, identity, start.pending(), state, verifier);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new McpOAuthAuthorizationResponse(start.authorizationUrl().toString()));
    }

    @PutMapping("/{serverId}/api-key")
    @ApiResponse(responseCode = "200", description = "Stored connection", useReturnTypeSchema = true)
    @Operation(operationId = "saveMcpConnectionApiKey", summary = "Store the User's own API key after listing the server with it")
    McpConnectionResponse apiKey(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                 @PathVariable UUID serverId, @RequestBody McpConnectionApiKeyRequest request) {
        return McpConnectionResponse.from(connections.saveApiKey(identity.actorId(), serverId, request.apiKey()));
    }

    @DeleteMapping("/{serverId}/connection")
    @ApiResponse(responseCode = "204", description = "Connection removed", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "disconnectMcpConnection", summary = "Remove the User's own credential and revoke an OAuth connection")
    void disconnect(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID serverId) {
        connections.disconnect(identity.actorId(), serverId);
    }
}
