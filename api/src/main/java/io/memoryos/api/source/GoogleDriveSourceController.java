package io.memoryos.api.source;

import io.memoryos.api.source.contract.CreateGoogleDriveSourceRequest;
import io.memoryos.api.source.contract.GoogleDriveConfigurationResponse;
import io.memoryos.api.source.contract.ReplaceGoogleDriveRootsRequest;
import io.memoryos.api.source.contract.SourceDetailResponse;
import io.memoryos.api.source.contract.SourceOperationResponse;
import io.memoryos.api.source.contract.UpdateGoogleDriveScheduleRequest;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveSourceService;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceManagementService;
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

@RestController
@RequestMapping("/api/sources")
@Tag(name = "Sources")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
final class GoogleDriveSourceController {
    private final GoogleDriveSourceService sources;
    private final SourceManagementService management;

    GoogleDriveSourceController(GoogleDriveSourceService sources, SourceManagementService management) {
        this.sources = sources; this.management = management;
    }

    @Operation(operationId = "createGoogleDriveSource", summary = "Create a Google Drive source using a reusable credential")
    @PostMapping(value = "/google-drive", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<SourceDetailResponse> create(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Valid @RequestBody CreateGoogleDriveSourceRequest body) {
        var sourceId = sources.create(identity.actorId(), body.name(), new CredentialId(body.credentialId()), body.scopeMode(), body.links());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(SourceDetailResponse.from(management.getSource(identity.actorId(), sourceId)));
    }

    @Operation(operationId = "getGoogleDriveConfiguration", summary = "Get Google Drive source configuration")
    @GetMapping("/{sourceId}/google-drive")
    ResponseEntity<GoogleDriveConfigurationResponse> configuration(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId) {
        return configuration(sources.configuration(identity.actorId(), new SourceId(sourceId)));
    }


    @Operation(operationId = "replaceGoogleDriveRoots", summary = "Replace Google Drive scope mode and selected roots atomically")
    @PutMapping(value = "/{sourceId}/google-drive/roots", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GoogleDriveConfigurationResponse> replaceRoots(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sourceId,
            @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody ReplaceGoogleDriveRootsRequest body) {
        return configuration(sources.replaceRoots(identity.actorId(), new SourceId(sourceId), revision(ifMatch), body.scopeMode(), body.links()));
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
