package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ChatSpreadsheetPreviewResponse;
import io.memoryos.api.chat.contract.ChatSpreadsheetSheetResponse;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat/file-artifacts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid file request", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Tenant membership or CSRF requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "File not accessible", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "503", description = "Storage unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatFileArtifactController {
    /** Only raster images display inline; every other generated file downloads under nosniff. */
    private static final Set<String> INLINE = Set.of("image/png", "image/jpeg", "image/webp");
    private final InterpreterService files;
    private final io.memoryos.chat.interpreter.PresentationPreviewService presentations;
    ChatFileArtifactController(InterpreterService files, io.memoryos.chat.interpreter.PresentationPreviewService presentations) {
        this.files = files;
        this.presentations = presentations;
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{artifactId}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteChatFileArtifact",
            summary = "Delete an owner-private generated file; the answer keeps a deleted card and a sweep releases the bytes")
    @ApiResponse(responseCode = "204", description = "File deleted")
    void delete(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID artifactId) {
        files.delete(identity.actorId(), artifactId);
    }

    @GetMapping(value = "/{artifactId}/pdf-preview", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(operationId = "getChatFileArtifactPdfPreview",
            summary = "Read a PDF rendering of an owner-private generated presentation, converted in the interpreter on first request")
    @ApiResponse(responseCode = "200", description = "PDF bytes",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE, schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "429", description = "The interpreter is busy", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
    void pdfPreview(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID artifactId, HttpServletResponse response) throws IOException {
        var served = presentations.pdf(identity.actorId(), artifactId);
        try (var content = served.content()) {
            response.setContentType(MediaType.APPLICATION_PDF_VALUE);
            response.setHeader("Content-Disposition", ContentDisposition.attachment()
                    .filename(served.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString());
            response.setContentLengthLong(content.metadata().sizeBytes());
            content.inputStream().transferTo(response.getOutputStream());
        }
    }

    @GetMapping(value = "/{artifactId}/chart", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getChatFileArtifactChart",
            summary = "Read the chart data captured from the figure behind an owner-private generated PNG")
    @ApiResponse(responseCode = "200", description = "Chart in the E2B chart model (type, title, elements, axes)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "object")))
    void chart(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID artifactId, HttpServletResponse response) throws IOException {
        byte[] body = files.chart(identity.actorId(), artifactId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    @GetMapping("/{artifactId}/preview")
    @Operation(operationId = "previewChatFileArtifactSpreadsheet",
            summary = "Read an owner-private generated xlsx as CSV text per sheet, each cut at a row boundary")
    @ApiResponse(responseCode = "200", description = "Sheets in workbook order",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ChatSpreadsheetPreviewResponse.class)))
    ChatSpreadsheetPreviewResponse preview(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID artifactId, HttpServletResponse response) {
        var sheets = files.spreadsheet(identity.actorId(), artifactId).stream()
                .map(sheet -> new ChatSpreadsheetSheetResponse(sheet.name(), sheet.csv(), sheet.truncated())).toList();
        return new ChatSpreadsheetPreviewResponse(sheets);
    }

    @GetMapping(value = "/{artifactId}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(operationId = "getChatFileArtifact", summary = "Read an owner-private file generated by Code Interpreter")
    @ApiResponse(responseCode = "200", description = "Generated file bytes",
            content = @Content(schema = @Schema(type = "string", format = "binary")))
    void content(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID artifactId, HttpServletResponse response) throws IOException {
        var served = files.open(identity.actorId(), artifactId);
        try (var content = served.content()) {
            boolean inline = INLINE.contains(served.mediaType());
            response.setContentType(served.mediaType());
            response.setHeader("Content-Disposition", (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                    .filename(served.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString());
            response.setContentLengthLong(content.metadata().sizeBytes());
            content.inputStream().transferTo(response.getOutputStream());
        }
    }
}
