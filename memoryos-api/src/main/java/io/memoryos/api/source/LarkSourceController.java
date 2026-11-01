package io.memoryos.api.source;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.source.contract.CreateLarkSourceRequest;
import io.memoryos.api.source.contract.LarkConfigurationResponse;
import io.memoryos.api.source.contract.LarkRootResponse;
import io.memoryos.api.source.contract.LarkSelectionReceiptResponse;
import io.memoryos.api.source.contract.LarkSpaceOptionResponse;
import io.memoryos.api.source.contract.ReplaceLarkScopeRequest;
import io.memoryos.api.source.contract.SourceOperationResponse;
import io.memoryos.api.source.contract.UpdateLarkPauseRequest;
import io.memoryos.api.source.contract.UpdateLarkScheduleRequest;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkSourceService;
import io.memoryos.connector.SourceId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@ApiResponse(responseCode = "400", description = "Invalid Lark scope")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required")
@ApiResponse(responseCode = "404", description = "Source unavailable")
@ApiResponse(responseCode = "409", description = "Source or credential changed")
final class LarkSourceController {
    private final LarkSourceService sources;

    LarkSourceController(LarkSourceService sources) {
        this.sources = sources;
    }

    @Operation(operationId = "createLarkSource",
            summary = "Create a Lark source; its scope is verified with Lark before it takes effect")
    @ApiResponse(responseCode = "202", description = "Scope accepted for verification", useReturnTypeSchema = true)
    @PostMapping(value = "/lark", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResponseEntity<LarkSelectionReceiptResponse> create(@CurrentActor IdentityContext identity,
            @Valid @RequestBody CreateLarkSourceRequest body) {
        var receipt = sources.create(identity.actorId(), body.requestId(), body.name(),
                new CredentialId(body.credentialId()), body.scope().toScope(), body.access(),
                body.groupIds() == null ? List.of() : body.groupIds().stream().map(GroupId::new).toList());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(LarkSelectionReceiptResponse.from(receipt));
    }

    @Operation(operationId = "listLarkSpaces",
            summary = "List the Wiki spaces the credential's app is a member of, read from Lark now")
    @ApiResponse(responseCode = "200", description = "Wiki spaces a source may name", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "503", description = "Lark did not answer")
    @GetMapping("/lark/credentials/{credentialId}/spaces")
    ResponseEntity<List<LarkSpaceOptionResponse>> spaces(@CurrentActor IdentityContext identity,
            @PathVariable UUID credentialId) {
        return ResponseEntity.ok().body(sources.spaces(identity.actorId(), new CredentialId(credentialId)).stream()
                .map(LarkSpaceOptionResponse::from).toList());
    }

    @Operation(operationId = "getLarkConfiguration", summary = "Get Lark source configuration")
    @ApiResponse(responseCode = "200", description = "Lark configuration", useReturnTypeSchema = true)
    @GetMapping("/{sourceId}/lark")
    ResponseEntity<LarkConfigurationResponse> configuration(@CurrentActor IdentityContext identity,
            @PathVariable UUID sourceId) {
        return configuration(sources.configuration(identity.actorId(), new SourceId(sourceId)));
    }

    @Operation(operationId = "getLarkRoots", summary = "List the Wiki spaces and Drive folders in scope")
    @ApiResponse(responseCode = "200", description = "Lark roots", useReturnTypeSchema = true)
    @GetMapping("/{sourceId}/lark/roots")
    ResponseEntity<List<LarkRootResponse>> roots(@CurrentActor IdentityContext identity, @PathVariable UUID sourceId) {
        return ResponseEntity.ok().body(sources.roots(identity.actorId(), new SourceId(sourceId)).stream()
                .map(LarkRootResponse::from).toList());
    }

    @Operation(operationId = "getLarkSelectionRequest", summary = "Recover the receipt of an accepted scope request")
    @ApiResponse(responseCode = "200", description = "Lark scope receipt", useReturnTypeSchema = true)
    @GetMapping("/lark/selection-requests/{requestId}")
    ResponseEntity<LarkSelectionReceiptResponse> receipt(@CurrentActor IdentityContext identity,
            @PathVariable UUID requestId) {
        return ResponseEntity.ok()
                .body(LarkSelectionReceiptResponse.from(sources.selectionRequest(identity.actorId(), requestId)));
    }

    @Operation(operationId = "replaceLarkScope",
            summary = "Replace what a Lark source synchronizes; the new scope is verified before it applies")
    @ApiResponse(responseCode = "202", description = "Scope accepted for verification", useReturnTypeSchema = true)
    @PutMapping(value = "/{sourceId}/lark/scope", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    ResponseEntity<LarkSelectionReceiptResponse> replaceScope(@CurrentActor IdentityContext identity,
            @PathVariable UUID sourceId, @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody ReplaceLarkScopeRequest body) {
        var receipt = sources.replaceScope(identity.actorId(), body.requestId(), new SourceId(sourceId),
                GoogleDriveSourceController.revision(ifMatch), body.expectedCredentialRevision(), body.scope().toScope());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(LarkSelectionReceiptResponse.from(receipt));
    }

    @Operation(operationId = "updateLarkSchedule", summary = "Update the automatic synchronization interval")
    @ApiResponse(responseCode = "200", description = "Updated Lark configuration", useReturnTypeSchema = true)
    @PutMapping(value = "/{sourceId}/lark/schedule", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LarkConfigurationResponse> updateSchedule(@CurrentActor IdentityContext identity,
            @PathVariable UUID sourceId, @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody UpdateLarkScheduleRequest body) {
        return schedule(sources.updateSchedule(identity.actorId(), new SourceId(sourceId),
                GoogleDriveSourceController.revision(ifMatch), body.syncIntervalMinutes()));
    }

    @Operation(operationId = "updateLarkPause", summary = "Pause or resume future automatic synchronization")
    @ApiResponse(responseCode = "200", description = "Updated Lark configuration", useReturnTypeSchema = true)
    @PostMapping(value = "/{sourceId}/lark/pause", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<LarkConfigurationResponse> updatePause(@CurrentActor IdentityContext identity,
            @PathVariable UUID sourceId, @Valid @RequestBody UpdateLarkPauseRequest body) {
        return schedule(sources.setPaused(identity.actorId(), new SourceId(sourceId), body.expectedRevision(),
                body.paused()));
    }

    @Operation(operationId = "synchronizeLarkSource", summary = "Schedule a synchronization run now")
    @ApiResponse(responseCode = "202", description = "Run scheduled", useReturnTypeSchema = true)
    @PostMapping("/{sourceId}/lark/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SourceOperationResponse synchronize(@CurrentActor IdentityContext identity, @PathVariable UUID sourceId) {
        return SourceOperationResponse.from(sources.synchronize(identity.actorId(), new SourceId(sourceId)));
    }

    private static ResponseEntity<LarkConfigurationResponse> configuration(LarkSourceService.Configuration value) {
        return ResponseEntity.ok().eTag("\"" + value.scopeRevision() + "\"").body(LarkConfigurationResponse.from(value));
    }

    private static ResponseEntity<LarkConfigurationResponse> schedule(LarkSourceService.Configuration value) {
        return ResponseEntity.ok().eTag("\"" + value.scheduleRevision() + "\"").body(LarkConfigurationResponse.from(value));
    }
}
