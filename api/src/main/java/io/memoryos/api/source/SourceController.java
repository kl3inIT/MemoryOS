package io.memoryos.api.source;

import io.memoryos.api.source.contract.CreateFileSourceRequest;
import io.memoryos.api.source.contract.InitiateSourceUploadRequest;
import io.memoryos.api.source.contract.SourceDetailResponse;
import io.memoryos.api.source.contract.SourceItemResponse;
import io.memoryos.api.source.contract.SourceOperationResponse;
import io.memoryos.api.source.contract.SourceSummaryResponse;
import io.memoryos.api.source.contract.SourceUploadAuthorizationResponse;
import io.memoryos.api.source.contract.SourceUploadReceiptResponse;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceManagementService;
import io.memoryos.iam.IdentityContext;
import io.memoryos.objectstorage.ObjectUploadId;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources")
@Tag(name = "Sources")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
final class SourceController {

    private final SourceManagementService sources;

    SourceController(SourceManagementService sources) {
        this.sources = sources;
    }

    @Operation(
            operationId = "createFileSource",
            summary = "Create a Tenant-owned FILE source"
    )
    @PostMapping(value = "/file", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SourceDetailResponse createFileSource(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @Valid @RequestBody CreateFileSourceRequest request
    ) {
        return SourceDetailResponse.from(sources.createFileSource(
                identityContext.actorId(),
                request.name(),
                request.toGroupIds()
        ));
    }

    @Operation(operationId = "listSources", summary = "List Tenant sources")
    @GetMapping
    List<SourceSummaryResponse> listSources(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext
    ) {
        return sources.listSources(identityContext.actorId()).stream()
                .map(SourceSummaryResponse::from)
                .toList();
    }

    @Operation(operationId = "getSource", summary = "Get one source with current items")
    @GetMapping("/{sourceId}")
    SourceDetailResponse getSource(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID sourceId
    ) {
        return SourceDetailResponse.from(sources.getSource(identityContext.actorId(), new SourceId(sourceId)));
    }

    @Operation(operationId = "listSourceItems", summary = "List current source items")
    @GetMapping("/{sourceId}/items")
    List<SourceItemResponse> listItems(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID sourceId
    ) {
        return sources.getSource(identityContext.actorId(), new SourceId(sourceId)).items().stream()
                .map(SourceItemResponse::from)
                .toList();
    }

    @Operation(operationId = "listSourceIndexAttempts", summary = "List source indexing attempts")
    @GetMapping("/{sourceId}/index-attempts")
    List<SourceOperationResponse> listIndexAttempts(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID sourceId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size
    ) {
        return sources.listIndexAttempts(identityContext.actorId(), new SourceId(sourceId), size).stream()
                .map(SourceOperationResponse::from)
                .toList();
    }

    @Operation(operationId = "initiateSourceUpload", summary = "Authorize one direct FILE source upload")
    @PostMapping(value = "/{sourceId}/uploads", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SourceUploadAuthorizationResponse initiateUpload(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID sourceId,
            @Valid @RequestBody InitiateSourceUploadRequest request
    ) {
        return SourceUploadAuthorizationResponse.from(sources.initiateUpload(
                identityContext.actorId(),
                new SourceId(sourceId),
                request.toSpecification()
        ));
    }

    @Operation(operationId = "finalizeSourceUpload", summary = "Verify and adopt one direct FILE source upload")
    @PostMapping("/{sourceId}/uploads/{uploadId}/finalize")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SourceUploadReceiptResponse finalizeUpload(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID sourceId,
            @PathVariable UUID uploadId
    ) {
        return SourceUploadReceiptResponse.from(sources.finalizeUpload(
                identityContext.actorId(),
                new SourceId(sourceId),
                new ObjectUploadId(uploadId)
        ));
    }

    @Operation(operationId = "reindexSourceItem", summary = "Reindex one source item")
    @PostMapping("/{sourceId}/items/{itemId}/index-attempts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SourceOperationResponse reindex(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID sourceId,
            @PathVariable UUID itemId
    ) {
        return SourceOperationResponse.from(sources.reindex(
                identityContext.actorId(),
                new SourceId(sourceId),
                new SourceItemId(itemId)
        ));
    }

    @Operation(operationId = "removeSourceItem", summary = "Start durable source item removal")
    @PostMapping("/{sourceId}/items/{itemId}/remove")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SourceOperationResponse removeItem(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID sourceId,
            @PathVariable UUID itemId
    ) {
        return SourceOperationResponse.from(sources.removeItem(
                identityContext.actorId(),
                new SourceId(sourceId),
                new SourceItemId(itemId)
        ));
    }

    @Operation(operationId = "deleteSource", summary = "Start durable source deletion")
    @PostMapping("/{sourceId}/delete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SourceOperationResponse deleteSource(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID sourceId
    ) {
        return SourceOperationResponse.from(sources.deleteSource(identityContext.actorId(), new SourceId(sourceId)));
    }
}
