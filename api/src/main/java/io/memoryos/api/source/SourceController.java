package io.memoryos.api.source;

import io.memoryos.api.source.contract.CreateFileSourceRequest;
import io.memoryos.api.source.contract.SourceDetailResponse;
import io.memoryos.api.source.contract.SourceItemResponse;
import io.memoryos.api.source.contract.SourceOperationResponse;
import io.memoryos.api.source.contract.SourceSummaryResponse;
import io.memoryos.api.source.contract.SourceUploadResponse;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceManagementService;
import io.memoryos.identity.IdentityContext;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.io.IOException;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
        return SourceDetailResponse.from(sources.createFileSource(identityContext.actorId(), request.name()));
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

    @Operation(operationId = "uploadSourceItem", summary = "Upload one bounded FILE source item")
    @PostMapping(value = "/{sourceId}/items", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    SourceUploadResponse upload(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @PathVariable UUID sourceId,
            @RequestPart("file") @NotNull MultipartFile file
    ) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw SourceException.invalid("The uploaded file could not be read.", "multipart file read failed");
        }
        String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        return SourceUploadResponse.from(sources.upload(
                identityContext.actorId(),
                new SourceId(sourceId),
                filename,
                content
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
