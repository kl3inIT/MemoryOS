package io.memoryos.api.chat;

import io.memoryos.chat.application.ChatExportService;
import io.memoryos.chat.persistence.JdbcChatExportRepository;
import io.memoryos.iam.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exporting one's own conversations and files (MEM-153): the request is recorded, a Worker packs the ZIP, and
 * the owner downloads it until it expires. Only the owner ever reads an export.
 */
@RestController
@RequestMapping(value = "/api/chat/exports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid export request", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "CSRF required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "No such export", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "409", description = "An export is already being prepared", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
class ChatExportController {
    private final ChatExportService exports;

    ChatExportController(ChatExportService exports) { this.exports = exports; }

    @Schema(name = "ChatExport")
    record ExportResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "PENDING, RUNNING, READY or FAILED") String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"})
            @Nullable Integer sessionCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"})
            @Nullable Integer fileCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Names of the files the export left out") List<String> skipped,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"})
            @Nullable Long sizeBytes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String failure,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "date-time") Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time")
            @Nullable Instant expiresAt) {

        static ExportResponse from(JdbcChatExportRepository.Export export) {
            return new ExportResponse(export.id(), export.status().name(), export.sessionCount(),
                    export.fileCount(), export.skipped(), export.sizeBytes(), export.failure(), export.createdAt(),
                    export.expiresAt());
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "requestChatExport",
            summary = "Ask for a ZIP of the caller's own conversations and files")
    @ApiResponse(responseCode = "202", description = "The export being prepared", useReturnTypeSchema = true)
    ExportResponse request(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return ExportResponse.from(exports.request(identity.actorId()));
    }

    @GetMapping
    @Operation(operationId = "listChatExports", summary = "The caller's exports that are still worth offering")
    @ApiResponse(responseCode = "200", description = "Exports", useReturnTypeSchema = true)
    List<ExportResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return exports.list(identity.actorId()).stream().map(ExportResponse::from).toList();
    }

    @GetMapping("/{exportId}")
    @Operation(operationId = "getChatExport", summary = "How far one export has got")
    @ApiResponse(responseCode = "200", description = "The export", useReturnTypeSchema = true)
    ExportResponse get(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID exportId) {
        return ExportResponse.from(exports.get(identity.actorId(), exportId));
    }

    @GetMapping(value = "/{exportId}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(operationId = "downloadChatExport", summary = "Download a finished export")
    @ApiResponse(responseCode = "200", description = "The ZIP",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE))
    ResponseEntity<InputStreamResource> content(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID exportId) {
        var download = exports.open(identity.actorId(), exportId);
        var metadata = download.content().metadata();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(metadata.sizeBytes())
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new InputStreamResource(download.content().inputStream()));
    }
}
