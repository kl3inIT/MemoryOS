package io.memoryos.api.source;

import io.memoryos.api.source.contract.CreateSharePointSourceRequest;
import io.memoryos.api.source.contract.ReplaceSharePointScopeRequest;
import io.memoryos.api.source.contract.SharePointConfigurationResponse;
import io.memoryos.api.source.contract.SharePointRootPageResponse;
import io.memoryos.api.source.contract.SharePointSelectionPolicyResponse;
import io.memoryos.api.source.contract.SharePointSelectionReceiptResponse;
import io.memoryos.api.source.contract.SourceOperationResponse;
import io.memoryos.api.source.contract.UpdateSharePointPauseRequest;
import io.memoryos.api.source.contract.UpdateSharePointScheduleRequest;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointSourceService;
import io.memoryos.connector.SourceId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creating and reconfiguring a SharePoint Source. Creation and scope changes are accepted for verification
 * against Microsoft and answer {@code 202} with a receipt, so a pasted address is never trusted here.
 */
@RestController
@RequestMapping("/api/sources")
@Tag(name = "Sources")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid SharePoint scope", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Source unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "409", description = "Source or credential changed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
final class SharePointSourceController {
    private final SharePointSourceService sources;

    SharePointSourceController(SharePointSourceService sources) {
        this.sources = sources;
    }

    @Operation(operationId = "createSharePointSource",
            summary = "Create a SharePoint source; its scope is verified with Microsoft before it takes effect")
    @ApiResponse(responseCode = "202", description = "Scope accepted for verification", useReturnTypeSchema = true)
    @PostMapping(value = "/sharepoint", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResponseEntity<SharePointSelectionReceiptResponse> create(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Valid @RequestBody CreateSharePointSourceRequest body) {
        var receipt = sources.create(identity.actorId(), body.requestId(), body.name(),
                new CredentialId(body.credentialId()), body.scope().toScope(), body.access(),
                body.groupIds() == null ? List.of() : body.groupIds().stream().map(GroupId::new).toList());
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(SharePointSelectionReceiptResponse.from(receipt));
    }

    @Operation(operationId = "getSharePointConfiguration", summary = "Get SharePoint source configuration")
    @ApiResponse(responseCode = "200", description = "SharePoint configuration", useReturnTypeSchema = true)
    @GetMapping("/{sourceId}/sharepoint")
    ResponseEntity<SharePointConfigurationResponse> configuration(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId) {
        return configuration(sources.configuration(identity.actorId(), new SourceId(sourceId)));
    }

    @Operation(operationId = "getSharePointRoots", summary = "Page the site, library and folder addresses in scope")
    @ApiResponse(responseCode = "200", description = "SharePoint roots", useReturnTypeSchema = true)
    @GetMapping("/{sourceId}/sharepoint/roots")
    ResponseEntity<SharePointRootPageResponse> roots(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SharePointRootPageResponse.from(sources.roots(identity.actorId(), new SourceId(sourceId), cursor, size)));
    }

    @Operation(operationId = "getSharePointSelectionPolicy", summary = "Get the configured scope admission limits")
    @ApiResponse(responseCode = "200", description = "SharePoint selection policy", useReturnTypeSchema = true)
    @GetMapping("/sharepoint/selection-policy")
    ResponseEntity<SharePointSelectionPolicyResponse> policy(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SharePointSelectionPolicyResponse.from(sources.selectionPolicy(identity.actorId())));
    }

    @Operation(operationId = "getSharePointSelectionRequest",
            summary = "Recover the receipt of an accepted scope request")
    @ApiResponse(responseCode = "200", description = "SharePoint scope receipt", useReturnTypeSchema = true)
    @GetMapping("/sharepoint/selection-requests/{requestId}")
    ResponseEntity<SharePointSelectionReceiptResponse> receipt(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID requestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(SharePointSelectionReceiptResponse.from(sources.selectionRequest(identity.actorId(), requestId)));
    }

    @Operation(operationId = "replaceSharePointScope",
            summary = "Replace what a SharePoint source synchronizes; the new scope is verified before it applies")
    @ApiResponse(responseCode = "202", description = "Scope accepted for verification", useReturnTypeSchema = true)
    @PutMapping(value = "/{sourceId}/sharepoint/scope", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResponseEntity<SharePointSelectionReceiptResponse> replaceScope(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId,
            @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody ReplaceSharePointScopeRequest body) {
        var receipt = sources.replaceScope(identity.actorId(), body.requestId(), new SourceId(sourceId),
                GoogleDriveSourceController.revision(ifMatch), body.expectedCredentialRevision(), body.scope().toScope());
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(SharePointSelectionReceiptResponse.from(receipt));
    }

    @Operation(operationId = "updateSharePointSchedule",
            summary = "Update the automatic synchronization and prune intervals")
    @ApiResponse(responseCode = "200", description = "Updated SharePoint configuration", useReturnTypeSchema = true)
    @PutMapping(value = "/{sourceId}/sharepoint/schedule", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SharePointConfigurationResponse> updateSchedule(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId,
            @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody UpdateSharePointScheduleRequest body) {
        var value = sources.updateSchedule(identity.actorId(), new SourceId(sourceId),
                GoogleDriveSourceController.revision(ifMatch), body.syncIntervalMinutes(), body.pruneIntervalHours());
        return schedule(value);
    }

    @Operation(operationId = "updateSharePointPause", summary = "Pause or resume future automatic synchronization")
    @ApiResponse(responseCode = "200", description = "Updated SharePoint configuration", useReturnTypeSchema = true)
    @PostMapping(value = "/{sourceId}/sharepoint/pause", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SharePointConfigurationResponse> updatePause(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId,
            @Valid @RequestBody UpdateSharePointPauseRequest body) {
        return schedule(sources.setPaused(identity.actorId(), new SourceId(sourceId), body.expectedRevision(), body.paused()));
    }

    @Operation(operationId = "synchronizeSharePointSource", summary = "Schedule a synchronization run now")
    @ApiResponse(responseCode = "202", description = "Run scheduled", useReturnTypeSchema = true)
    @PostMapping("/{sourceId}/sharepoint/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SourceOperationResponse synchronize(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId) {
        return SourceOperationResponse.from(sources.synchronize(identity.actorId(), new SourceId(sourceId)));
    }

    private static ResponseEntity<SharePointConfigurationResponse> configuration(SharePointSourceService.Configuration value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"" + value.scopeRevision() + "\"")
                .body(SharePointConfigurationResponse.from(value));
    }

    private static ResponseEntity<SharePointConfigurationResponse> schedule(SharePointSourceService.Configuration value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"" + value.scheduleRevision() + "\"")
                .body(SharePointConfigurationResponse.from(value));
    }
}
