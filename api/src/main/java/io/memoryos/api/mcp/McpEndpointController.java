package io.memoryos.api.mcp;

import io.memoryos.api.mcp.contract.McpEndpointSettingsRequest;
import io.memoryos.api.mcp.contract.McpEndpointSettingsResponse;
import io.memoryos.api.security.CurrentActor;
import io.memoryos.iam.IdentityContext;
import io.memoryos.mcp.McpEndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** MEM-114: the per-Tenant switch of the MemoryOS MCP endpoint. */
@RestController
@RequestMapping(value = "/api/mcp/endpoint", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "MCP")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid MCP endpoint setting")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "MCP management, Tenant membership or CSRF requirement not met")
@ApiResponse(responseCode = "404", description = "Tenant unavailable")
@ApiResponse(responseCode = "409", description = "MCP endpoint setting changed")
@ApiResponse(responseCode = "503", description = "This deployment has no MCP endpoint URL")
class McpEndpointController {
    private final McpEndpointService endpoint;

    McpEndpointController(McpEndpointService endpoint) {
        this.endpoint = endpoint;
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "MCP endpoint setting", useReturnTypeSchema = true)
    @Operation(operationId = "getMcpEndpointSettings",
            summary = "Read whether the MemoryOS MCP endpoint is configured and on for the Tenant")
    McpEndpointSettingsResponse get(@CurrentActor IdentityContext identity) {
        return McpEndpointSettingsResponse.from(endpoint.settings(identity.actorId()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "Saved MCP endpoint setting", useReturnTypeSchema = true)
    @Operation(operationId = "updateMcpEndpointSettings", summary = "Turn the MemoryOS MCP endpoint on or off for the Tenant")
    McpEndpointSettingsResponse update(@CurrentActor IdentityContext identity,
                                       @Valid @RequestBody McpEndpointSettingsRequest request) {
        return McpEndpointSettingsResponse.from(endpoint.update(identity.actorId(), request.enabled(), request.revision()));
    }
}
