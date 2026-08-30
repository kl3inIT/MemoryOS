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
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sources")
@Tag(name = "Sources")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
final class SourceController {

    private static final String BROWSER_REQUEST_HEADER = "X-MemoryOS-CSRF";
    private static final String BROWSER_REQUEST_VALUE = "1";

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
            @Parameter(
                    name = BROWSER_REQUEST_HEADER,
                    description = "Same-origin non-simple request guard for browser-session mutations.",
                    required = true,
                    schema = @Schema(allowableValues = BROWSER_REQUEST_VALUE)
            )
            @RequestHeader(value = BROWSER_REQUEST_HEADER, required = false) String browserRequest,
            @Valid @RequestBody CreateFileSourceRequest request
    ) {
        requireBrowserMutation(browserRequest);
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
        return sources.listIndexOperations(identityContext.actorId(), new SourceId(sourceId)).stream()
                .limit(size)
                .map(SourceOperationResponse::from)
                .toList();
    }

    @Operation(operationId = "uploadSourceItem", summary = "Upload one bounded FILE source item")
    @PostMapping(value = "/{sourceId}/items", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    SourceUploadResponse upload(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @Parameter(
                    name = BROWSER_REQUEST_HEADER,
                    description = "Same-origin non-simple request guard for browser-session mutations.",
                    required = true,
                    schema = @Schema(allowableValues = BROWSER_REQUEST_VALUE)
            )
            @RequestHeader(value = BROWSER_REQUEST_HEADER, required = false) String browserRequest,
            @PathVariable UUID sourceId,
            @RequestPart("file") @NotNull MultipartFile file
    ) {
        requireBrowserMutation(browserRequest);
        if (file.isEmpty() || file.getSize() > 10L * 1024 * 1024) {
            throw SourceException.invalid(
                    "Upload one file between 1 byte and 10 MiB.",
                    "multipart file was empty or exceeded 10 MiB"
            );
        }
        try {
            byte[] content = file.getBytes();
            String filename = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
            return SourceUploadResponse.from(sources.upload(
                    identityContext.actorId(),
                    new SourceId(sourceId),
                    filename,
                    content,
                    sha256(content)
            ));
        } catch (IOException exception) {
            throw SourceException.invalid("The uploaded file could not be read.", "multipart file read failed");
        }
    }

    @Operation(operationId = "reindexSourceItem", summary = "Reindex one source item")
    @PostMapping("/{sourceId}/items/{itemId}/index-attempts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SourceOperationResponse reindex(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identityContext,
            @Parameter(
                    name = BROWSER_REQUEST_HEADER,
                    description = "Same-origin non-simple request guard for browser-session mutations.",
                    required = true,
                    schema = @Schema(allowableValues = BROWSER_REQUEST_VALUE)
            )
            @RequestHeader(value = BROWSER_REQUEST_HEADER, required = false) String browserRequest,
            @PathVariable UUID sourceId,
            @PathVariable UUID itemId
    ) {
        requireBrowserMutation(browserRequest);
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
            @Parameter(
                    name = BROWSER_REQUEST_HEADER,
                    description = "Same-origin non-simple request guard for browser-session mutations.",
                    required = true,
                    schema = @Schema(allowableValues = BROWSER_REQUEST_VALUE)
            )
            @RequestHeader(value = BROWSER_REQUEST_HEADER, required = false) String browserRequest,
            @PathVariable UUID sourceId,
            @PathVariable UUID itemId
    ) {
        requireBrowserMutation(browserRequest);
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
            @Parameter(
                    name = BROWSER_REQUEST_HEADER,
                    description = "Same-origin non-simple request guard for browser-session mutations.",
                    required = true,
                    schema = @Schema(allowableValues = BROWSER_REQUEST_VALUE)
            )
            @RequestHeader(value = BROWSER_REQUEST_HEADER, required = false) String browserRequest,
            @PathVariable UUID sourceId
    ) {
        requireBrowserMutation(browserRequest);
        return SourceOperationResponse.from(sources.deleteSource(identityContext.actorId(), new SourceId(sourceId)));
    }

    private static void requireBrowserMutation(String browserRequest) {
        if (!BROWSER_REQUEST_VALUE.equals(browserRequest)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Same-origin mutation header is required");
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
