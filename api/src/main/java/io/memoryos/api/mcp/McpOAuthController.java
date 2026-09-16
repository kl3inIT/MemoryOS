package io.memoryos.api.mcp;

import io.memoryos.api.mcp.contract.McpOAuthAuthorizationRequest;
import io.memoryos.api.mcp.contract.McpOAuthAuthorizationResponse;
import io.memoryos.api.mcp.contract.McpOAuthClientRequest;
import io.memoryos.api.mcp.contract.McpOAuthClientResponse;
import io.memoryos.api.mcp.contract.McpOAuthDiscoveryResponse;
import io.memoryos.api.mcp.contract.McpOAuthRegistrationRequest;
import io.memoryos.iam.identity.IdentityContext;
import io.memoryos.mcp.McpOAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/mcp/servers/{serverId}/oauth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "MCP")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid OAuth configuration or unusable authorization-server metadata", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "MCP management, Tenant membership or CSRF requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "MCP server or OAuth client not accessible", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "409", description = "Stale revision or duplicate label", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "503", description = "Authorization server, redirect URI or encryption key unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
class McpOAuthController {
    private final McpOAuthService oauth;

    McpOAuthController(McpOAuthService oauth) {
        this.oauth = oauth;
    }

    @PostMapping("/discovery")
    @ApiResponse(responseCode = "200", description = "Discovered authorization servers for review", useReturnTypeSchema = true)
    @Operation(operationId = "discoverMcpServerOAuth", summary = "Explicitly discover the server's authorization servers; nothing is saved")
    McpOAuthDiscoveryResponse discover(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                       @PathVariable UUID serverId) {
        return McpOAuthDiscoveryResponse.from(oauth.discover(identity.actorId(), serverId));
    }

    @GetMapping("/clients")
    @ApiResponse(responseCode = "200", description = "OAuth clients with secrets redacted", useReturnTypeSchema = true)
    @Operation(operationId = "listMcpServerOAuthClients", summary = "List the server's labelled OAuth clients; requires MCP_MANAGE")
    List<McpOAuthClientResponse> clients(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                         @PathVariable UUID serverId) {
        return oauth.clients(identity.actorId(), serverId).stream().map(McpOAuthClientResponse::from).toList();
    }

    @PostMapping("/clients")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createMcpServerOAuthClient", summary = "Add a pre-registered OAuth client, e.g. one organization's Internal app")
    McpOAuthClientResponse createClient(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                        @PathVariable UUID serverId, @RequestBody McpOAuthClientRequest request) {
        return McpOAuthClientResponse.from(oauth.createClient(identity.actorId(), serverId, request.toInput()));
    }

    @PostMapping("/clients/registrations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "registerMcpServerOAuthClient", summary = "Register with a discovered authorization server by DCR or the client metadata document")
    McpOAuthClientResponse register(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                    @PathVariable UUID serverId, @RequestBody McpOAuthRegistrationRequest request) {
        return McpOAuthClientResponse.from(oauth.createDiscoveredClient(identity.actorId(), serverId, request.label(),
                request.issuer(), request.source()));
    }

    @PutMapping("/clients/{clientId}")
    @ApiResponse(responseCode = "200", description = "Saved OAuth client", useReturnTypeSchema = true)
    @Operation(operationId = "updateMcpServerOAuthClient", summary = "Replace a pre-registered OAuth client at the expected revision")
    McpOAuthClientResponse updateClient(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                        @PathVariable UUID serverId, @PathVariable UUID clientId,
                                        @RequestParam @Positive long revision, @RequestBody McpOAuthClientRequest request) {
        return McpOAuthClientResponse.from(oauth.updateClient(identity.actorId(), serverId, clientId, revision, request.toInput()));
    }

    @DeleteMapping("/clients/{clientId}")
    @ApiResponse(responseCode = "204", description = "OAuth client and its connections deleted", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteMcpServerOAuthClient", summary = "Delete an OAuth client and the connections made with it")
    void deleteClient(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                      @PathVariable UUID serverId, @PathVariable UUID clientId, @RequestParam @Positive long revision) {
        oauth.deleteClient(identity.actorId(), serverId, clientId, revision);
    }

    @PostMapping("/authorization")
    @ApiResponse(responseCode = "200", description = "Authorization URL for the browser", useReturnTypeSchema = true)
    @Operation(operationId = "startMcpServerOAuthAuthorization", summary = "Start the administrator's OAuth connection for a shared-connection server")
    ResponseEntity<McpOAuthAuthorizationResponse> authorize(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                                            @PathVariable UUID serverId, @RequestBody McpOAuthAuthorizationRequest request,
                                                            HttpServletRequest servletRequest) {
        McpAuthorizationSessionState.requireSession(servletRequest, identity);
        String state = McpAuthorizationSessionState.random();
        String verifier = McpAuthorizationSessionState.random();
        var start = oauth.startAdministratorAuthorization(identity.actorId(), serverId, request.oauthClientId(), state,
                McpAuthorizationSessionState.challenge(verifier));
        McpAuthorizationSessionState.store(servletRequest, identity, start.pending(), state, verifier);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new McpOAuthAuthorizationResponse(start.authorizationUrl().toString()));
    }

    @DeleteMapping("/connection")
    @ApiResponse(responseCode = "204", description = "Shared connection removed and revoked best effort", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "disconnectMcpServerOAuth", summary = "Remove the shared OAuth connection and revoke it at the authorization server")
    void disconnect(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID serverId) {
        oauth.disconnectAdministrator(identity.actorId(), serverId);
    }
}
