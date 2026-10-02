package io.memoryos.api.mcp;

import io.memoryos.api.mcp.contract.McpEndpointCallPageResponse;
import io.memoryos.api.mcp.contract.McpEndpointInsightsResponse;
import io.memoryos.api.mcp.contract.McpEndpointSettingsRequest;
import io.memoryos.api.mcp.contract.McpEndpointSettingsResponse;
import io.memoryos.api.mcp.contract.McpTrustedAppEnabledRequest;
import io.memoryos.api.mcp.contract.McpTrustedAppListResponse;
import io.memoryos.api.mcp.contract.McpTrustedAppRequest;
import io.memoryos.api.mcp.contract.McpTrustedAppResponse;
import io.memoryos.api.security.CurrentActor;
import io.memoryos.iam.IdentityContext;
import io.memoryos.iam.McpClientGrant;
import io.memoryos.mcp.McpEndpointActivity;
import io.memoryos.mcp.McpEndpointService;
import io.memoryos.mcp.McpTrustedAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
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

/**
 * MEM-114: the per-Tenant switch of the MemoryOS MCP endpoint. MEM-207: the apps it admits. MEM-209: its activity log
 * and use, which administrators read with {@code MCP_MANAGE}.
 */
@RestController
@Validated
@RequestMapping(value = "/api/mcp/endpoint", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "MCP")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid MCP endpoint setting")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "MCP management, Tenant membership or CSRF requirement not met")
@ApiResponse(responseCode = "404", description = "Tenant unavailable")
@ApiResponse(responseCode = "409", description = "MCP endpoint setting changed")
@ApiResponse(responseCode = "503", description = "This deployment has no MCP endpoint URL, or no account to change "
        + "trusted apps in Keycloak, or Keycloak is unavailable")
class McpEndpointController {
    private final McpEndpointService endpoint;
    private final McpTrustedAppService trustedApps;
    private final McpEndpointActivity activity;

    McpEndpointController(McpEndpointService endpoint, McpTrustedAppService trustedApps, McpEndpointActivity activity) {
        this.endpoint = endpoint;
        this.trustedApps = trustedApps;
        this.activity = activity;
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

    @GetMapping("/trusted-apps")
    @ApiResponse(responseCode = "200", description = "Claude and ChatGPT, then the organization's own apps",
            useReturnTypeSchema = true)
    @Operation(operationId = "listMcpTrustedApps", summary = "List the apps the MemoryOS MCP endpoint admits")
    McpTrustedAppListResponse trustedApps(@CurrentActor IdentityContext identity) {
        return McpTrustedAppListResponse.from(trustedApps.list(identity.actorId()));
    }

    @PostMapping(value = "/trusted-apps", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "Trusted app added", useReturnTypeSchema = true)
    @Operation(operationId = "addMcpTrustedApp",
            summary = "Trust an app of the organization's own by the domains of its client metadata document")
    McpTrustedAppResponse addTrustedApp(@CurrentActor IdentityContext identity,
                                        @Valid @RequestBody McpTrustedAppRequest request) {
        return McpTrustedAppResponse.from(trustedApps.add(identity.actorId(), request.name(), request.clientIdHosts(),
                request.documentHosts()));
    }

    @PutMapping(value = "/trusted-apps/{appId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "Trusted app switched", useReturnTypeSchema = true)
    @Operation(operationId = "setMcpTrustedAppEnabled",
            summary = "Switch a trusted app on or off; off removes its clients and every grant to them")
    McpTrustedAppResponse setTrustedAppEnabled(@CurrentActor IdentityContext identity, @PathVariable UUID appId,
                                               @Valid @RequestBody McpTrustedAppEnabledRequest request) {
        return McpTrustedAppResponse.from(trustedApps.setEnabled(identity.actorId(), appId, request.enabled(),
                request.revision()));
    }

    @DeleteMapping("/trusted-apps/{appId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Trusted app removed", content = @Content)
    @Operation(operationId = "removeMcpTrustedApp",
            summary = "Remove an app of the organization's own, its clients and every grant to them")
    void removeTrustedApp(@CurrentActor IdentityContext identity, @PathVariable UUID appId,
                          @Parameter(description = "The app's revision as listed") @RequestParam @Positive long revision) {
        trustedApps.remove(identity.actorId(), appId, revision);
    }

    @GetMapping("/activity")
    @ApiResponse(responseCode = "200", description = "Tool calls, newest first", useReturnTypeSchema = true)
    @Operation(operationId = "listMcpEndpointActivity",
            summary = "Read the MCP endpoint's activity log: who called which tool through which app, kept 90 days")
    McpEndpointCallPageResponse activity(
            @CurrentActor IdentityContext identity,
            @Parameter(description = "Calls at or after this instant") @RequestParam(required = false) @Nullable Instant from,
            @Parameter(description = "Calls before this instant") @RequestParam(required = false) @Nullable Instant to,
            @RequestParam(required = false) McpClientGrant.@Nullable Client client,
            @RequestParam(required = false) @Size(max = 64) @Nullable String tool,
            @RequestParam(required = false) McpEndpointActivity.@Nullable Outcome outcome,
            @Parameter(description = "Part of the person's name or e-mail address")
            @RequestParam(required = false) @Size(max = 200) @Nullable String person,
            @Parameter(description = "The next value of the previous page")
            @RequestParam(required = false) @Size(max = 200) @Nullable String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return McpEndpointCallPageResponse.from(activity.page(identity.actorId(),
                new McpEndpointActivity.Filter(from, to, client, tool, outcome, person), cursor, size));
    }

    @GetMapping("/insights")
    @ApiResponse(responseCode = "200", description = "Use of the endpoint", useReturnTypeSchema = true)
    @Operation(operationId = "getMcpEndpointInsights",
            summary = "Read calls, people, apps and tools of the MCP endpoint over the last 7 or 30 days")
    McpEndpointInsightsResponse insights(@CurrentActor IdentityContext identity,
                                         @RequestParam(defaultValue = "7") @Min(7) @Max(30) int days) {
        return McpEndpointInsightsResponse.from(activity.insights(identity.actorId(), days));
    }
}
