package io.memoryos.api.source;

import io.memoryos.api.source.contract.CreateGoogleDriveSourceRequest;
import io.memoryos.api.source.contract.GoogleDriveConfigurationResponse;
import io.memoryos.api.source.contract.ReplaceGoogleDriveRootsRequest;
import io.memoryos.api.source.contract.GoogleDriveSelectionReceiptResponse;
import io.memoryos.api.source.contract.GoogleDriveSelectionResponse;
import io.memoryos.api.source.contract.GoogleDriveSelectionTreeResponse;
import io.memoryos.api.source.contract.SourceOperationResponse;
import io.memoryos.api.source.contract.UpdateGoogleDriveScheduleRequest;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveSourceService;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.jspecify.annotations.Nullable;

@RestController
@RequestMapping("/api/sources")
@Tag(name = "Sources")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
final class GoogleDriveSourceController {
    private final GoogleDriveSourceService sources;
    GoogleDriveSourceController(GoogleDriveSourceService sources) { this.sources = sources; }

    @Operation(operationId = "createGoogleDriveSource", summary = "Create a Google Drive source using a reusable credential")
    @PostMapping(value = "/google-drive", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResponseEntity<GoogleDriveSelectionReceiptResponse> create(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Valid @RequestBody CreateGoogleDriveSourceRequest body) {
        var receipt = sources.create(identity.actorId(), body.requestId(), body.name(), new CredentialId(body.credentialId()), body.scopeMode(), body.links());
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(GoogleDriveSelectionReceiptResponse.from(receipt));
    }

    @Operation(operationId = "getGoogleDriveConfiguration", summary = "Get Google Drive source configuration")
    @GetMapping("/{sourceId}/google-drive")
    ResponseEntity<GoogleDriveConfigurationResponse> configuration(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId) {
        return configuration(sources.configuration(identity.actorId(), new SourceId(sourceId)));
    }

    @Operation(operationId = "getGoogleDriveSelectionPolicy", summary = "Get the configured selection admission limits")
    @GetMapping("/google-drive/selection-policy")
    ResponseEntity<GoogleDriveSelectionResponse.Policy> policy(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(GoogleDriveSelectionResponse.Policy.from(sources.selectionPolicy(identity.actorId())));
    }

    @Operation(operationId = "getGoogleDriveSelectionRequest", summary = "Recover an accepted selection receipt for the initiating owner")
    @GetMapping("/google-drive/selection-requests/{requestId}")
    ResponseEntity<GoogleDriveSelectionReceiptResponse> receipt(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID requestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(GoogleDriveSelectionReceiptResponse.from(sources.selectionRequest(identity.actorId(), requestId)));
    }

    @Operation(operationId = "getGoogleDriveSelectionDraft", summary = "Get the complete bounded active selection for editing")
    @GetMapping("/{sourceId}/google-drive/selection-draft")
    ResponseEntity<GoogleDriveSelectionResponse.Draft> draft(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(GoogleDriveSelectionResponse.Draft.from(sources.selectionDraft(identity.actorId(), new SourceId(sourceId))));
    }

    @Operation(operationId = "getGoogleDriveSelection", summary = "Page selected roots and verified linked documents within a pinned Source snapshot")
    @GetMapping("/{sourceId}/google-drive/selection")
    ResponseEntity<GoogleDriveSelectionResponse> selection(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId,
            @RequestParam(required = false) @Nullable String search,
            @RequestParam(required = false) GoogleDriveSourceService.@Nullable SelectionKind kind,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(GoogleDriveSelectionResponse.from(sources.selection(identity.actorId(), new SourceId(sourceId), search, kind, cursor, size)));
    }

    @Operation(operationId = "getGoogleDriveSelectionTree", summary = "Expand selected Google Drive folders, files, and discovered links within the Source scope")
    @GetMapping("/{sourceId}/google-drive/selection-tree")
    ResponseEntity<GoogleDriveSelectionTreeResponse> selectionTree(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId,
            @RequestParam(required = false) @Nullable String parentId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(GoogleDriveSelectionTreeResponse.from(sources.selectionTree(identity.actorId(), new SourceId(sourceId), parentId, cursor, size)));
    }


    @Operation(operationId = "replaceGoogleDriveRoots", summary = "Replace selected Google Drive roots without changing the creation-time scope mode")
    @PutMapping(value = "/{sourceId}/google-drive/roots", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResponseEntity<GoogleDriveSelectionReceiptResponse> replaceRoots(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId,
            @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody ReplaceGoogleDriveRootsRequest body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
                .body(GoogleDriveSelectionReceiptResponse.from(sources.replaceRoots(identity.actorId(), body.requestId(),
                        new SourceId(sourceId), revision(ifMatch), body.discoveryRevision(), body.credentialRevision(),
                        body.scopeMode(), body.links(), body.linkedDocumentIds())));
    }

    @Operation(operationId = "discoverGoogleDriveLinkedDocuments", summary = "Discover linked documents without approving or synchronizing them")
    @PostMapping("/{sourceId}/google-drive/linked-documents/discover")
    ResponseEntity<GoogleDriveConfigurationResponse> discoverLinkedDocuments(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId,
            @RequestHeader("If-Match") String ifMatch) {
        return configuration(sources.discoverLinkedDocuments(identity.actorId(), new SourceId(sourceId), revision(ifMatch)));
    }

    @Operation(operationId = "updateGoogleDriveSchedule", summary = "Update the automatic Google Drive sync interval")
    @PutMapping(value = "/{sourceId}/google-drive/schedule", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GoogleDriveConfigurationResponse> updateSchedule(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId,
            @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody UpdateGoogleDriveScheduleRequest body) {
        var value = sources.updateSchedule(identity.actorId(), new SourceId(sourceId), revision(ifMatch), body.syncIntervalMinutes());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"" + value.scheduleRevision() + "\"")
                .body(GoogleDriveConfigurationResponse.from(value));
    }

    @Operation(operationId = "synchronizeGoogleDriveSource", summary = "Schedule durable Google Drive synchronization")
    @PostMapping("/{sourceId}/google-drive/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SourceOperationResponse synchronize(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId) {
        return SourceOperationResponse.from(sources.synchronize(identity.actorId(), new SourceId(sourceId)));
    }


    private static ResponseEntity<GoogleDriveConfigurationResponse> configuration(GoogleDriveSourceService.Configuration value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"" + value.revision() + "\"")
                .body(GoogleDriveConfigurationResponse.from(value));
    }

    static long revision(String value) {
        if (value == null || !value.matches("\"[0-9]{1,19}\"")) throw invalidRevision();
        try { return Long.parseLong(value.substring(1, value.length() - 1)); }
        catch (NumberFormatException exception) { throw invalidRevision(); }
    }
    private static SourceException invalidRevision() {
        return SourceException.invalid("If-Match must contain one quoted numeric revision.", "invalid Drive revision precondition");
    }
}
