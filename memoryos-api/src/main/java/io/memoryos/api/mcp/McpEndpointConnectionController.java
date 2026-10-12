package io.memoryos.api.mcp;

import io.memoryos.api.mcp.contract.McpClientGrantResponse;
import io.memoryos.api.mcp.contract.McpEndpointConnectionResponse;
import io.memoryos.api.security.CurrentActor;
import io.memoryos.iam.IdentityContext;
import io.memoryos.iam.McpClientGrants;
import io.memoryos.mcp.McpEndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MEM-114: the member's side of the MemoryOS MCP endpoint: where to point Claude or ChatGPT, and the clients they
 * allowed. A client ID travels as a query parameter, because Claude's is a URL.
 */
@RestController
@Tag(name = "MCP")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
class McpEndpointConnectionController {
    private final McpEndpointService endpoint;
    private final McpClientGrants grants;

    McpEndpointConnectionController(McpEndpointService endpoint, McpClientGrants grants) {
        this.endpoint = endpoint;
        this.grants = grants;
    }

    @GetMapping(value = "/api/mcp/endpoint/connection", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "Whether the endpoint answers, and its URL", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "403", description = "Search use or Tenant membership requirement not met")
    @ApiResponse(responseCode = "404", description = "Tenant unavailable")
    @Operation(operationId = "getMcpEndpointConnection",
            summary = "Read whether the signed-in User can connect Claude or ChatGPT to MemoryOS, and the URL to use")
    McpEndpointConnectionResponse connection(@CurrentActor IdentityContext identity) {
        return McpEndpointConnectionResponse.from(endpoint.connection(identity.actorId()));
    }

    @GetMapping(value = "/api/mcp/grants", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "Clients the User allowed, newest first", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "503", description = "Keycloak unavailable")
    @Operation(operationId = "listMcpClientGrants",
            summary = "List the outside assistants the signed-in User allowed to read MemoryOS")
    List<McpClientGrantResponse> grants(@CurrentActor IdentityContext identity) {
        return grants.list(identity.actorId()).stream().map(McpClientGrantResponse::from).toList();
    }

    @DeleteMapping("/api/mcp/grants")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Grant revoked", content = @Content)
    @ApiResponse(responseCode = "400", description = "Invalid client ID")
    @ApiResponse(responseCode = "403", description = "CSRF requirement not met")
    @ApiResponse(responseCode = "404", description = "The User has no grant to this client")
    @ApiResponse(responseCode = "503", description = "Keycloak unavailable")
    @Operation(operationId = "revokeMcpClientGrant",
            summary = "Revoke the signed-in User's grant to one outside assistant and end its offline session")
    void revoke(@CurrentActor IdentityContext identity,
                @Parameter(description = "The clientId of a listed grant")
                @RequestParam @Size(min = 1, max = 2048) String clientId) {
        grants.revoke(identity.actorId(), clientId);
    }
}
