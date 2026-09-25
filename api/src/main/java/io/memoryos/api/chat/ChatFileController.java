package io.memoryos.api.chat;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.chat.contract.ChatSpreadsheetPreviewResponse;
import io.memoryos.api.chat.contract.ChatSpreadsheetSheetResponse;
import io.memoryos.api.chat.contract.ChatFilePolicyResponse;
import io.memoryos.api.chat.contract.ChatFileResponse;
import io.memoryos.api.chat.contract.ChatFileUploadRequest;
import io.memoryos.api.chat.contract.ChatFileUploadResponse;
import io.memoryos.library.UserFileService;
import io.memoryos.library.UserFileContentService;
import io.memoryos.api.chat.contract.ChatFileTextResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import io.memoryos.library.UserFileSearchService;
import io.memoryos.library.UserFile;

@RestController
@RequestMapping(value="/api/chat/files", produces=MediaType.APPLICATION_JSON_VALUE)
@Tag(name="Chat")
@ApiResponse(responseCode="400", description="Invalid file request")
@ApiResponse(responseCode="403", description="Tenant membership or CSRF requirement not met")
@ApiResponse(responseCode="404", description="File not accessible")
@ApiResponse(responseCode="409", description="File state or request identity conflict")
@ApiResponse(responseCode="503", description="Storage unavailable")
@ApiResponse(responseCode="401", description="Authentication required", content=@Content)
@SecurityRequirement(name="browserSession")
@SecurityRequirement(name="bearerAuth")
class ChatFileController {
    private final UserFileService files;
    private final UserFileContentService content;
    private final UserFileSearchService search;
    ChatFileController(UserFileService files, UserFileContentService content, UserFileSearchService search) {
        this.files = files; this.content = content; this.search = search;
    }

    @GetMapping("/{fileId}/text")
    @Operation(operationId="readChatFileText", summary="Read a bounded window of owner-private extracted text")
    @ApiResponse(responseCode="200",description="Extracted text window",useReturnTypeSchema=true)
    ResponseEntity<ChatFileTextResponse> text(@CurrentActor IdentityContext identity,
            @PathVariable UUID fileId, @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="16000") int count) {
        return ResponseEntity.ok()
                .body(ChatFileTextResponse.from(files.read(identity.actorId(), fileId, offset, count)));
    }

    @GetMapping("/{fileId}/passages")
    @Operation(operationId="readChatFilePassages", summary="Read current owner-private indexed file passages at a cited position")
    @ApiResponse(responseCode="200",description="Authorized file passages",useReturnTypeSchema=true)
    ResponseEntity<io.memoryos.retrieval.SearchDocument> passages(@CurrentActor IdentityContext identity,
            @PathVariable UUID fileId, @RequestParam UUID generation, @RequestParam(defaultValue="0") int from) {
        return ResponseEntity.ok().body(search.read(identity.actorId(), fileId, generation, from));
    }

    @GetMapping("/{fileId}/preview")
    @Operation(operationId="previewChatFileSpreadsheet", summary="Read an owner-private xlsx attachment as CSV text per sheet, each cut at a row boundary")
    @ApiResponse(responseCode="200",description="Sheets in workbook order",content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE, schema=@Schema(implementation=ChatSpreadsheetPreviewResponse.class)))
    ResponseEntity<ChatSpreadsheetPreviewResponse> preview(@CurrentActor IdentityContext identity,
            @PathVariable UUID fileId) {
        var sheets = content.spreadsheet(identity.actorId(), fileId).stream()
                .map(sheet -> new ChatSpreadsheetSheetResponse(sheet.name(), sheet.csv(), sheet.truncated())).toList();
        return ResponseEntity.ok().body(new ChatSpreadsheetPreviewResponse(sheets));
    }

    @GetMapping(value="/{fileId}/content", produces=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(operationId="downloadChatFile", summary="Download an owner-private original file without inline execution")
    @ApiResponse(responseCode="200",description="Original file bytes",content=@Content(mediaType=MediaType.APPLICATION_OCTET_STREAM_VALUE, schema=@Schema(type="string",format="binary")))
    void download(@CurrentActor IdentityContext identity,
            @PathVariable UUID fileId, HttpServletResponse response) throws IOException {
        var file = files.get(identity.actorId(), fileId);
        try (var input = content.open(identity.actorId(), fileId)) {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader("Content-Disposition", ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString());
            response.setContentLengthLong(file.sizeBytes());
            input.inputStream().transferTo(response.getOutputStream());
        }
    }

    @GetMapping(value="/{fileId}/thumbnail", produces={"image/jpeg","image/png","image/webp"})
    @Operation(operationId="getChatFileThumbnail", summary="Read the file library's small rendering of an owner-private uploaded image")
    @ApiResponse(responseCode="200",description="Thumbnail bytes, or the original image when no smaller rendering could be made",content=@Content(schema=@Schema(type="string",format="binary")))
    void thumbnail(@CurrentActor IdentityContext identity,
            @PathVariable UUID fileId, HttpServletResponse response) throws IOException {
        try (var served = content.thumbnail(identity.actorId(), fileId)) {
            response.setContentType(served.mediaType());
            // An upload's bytes never change under its id, and the route authorizes every read, so the owner's
            // own browser may keep them. A shared cache must not: the response is owner-private.
            response.setHeader("Cache-Control", "private, max-age=31536000, immutable");
            response.setHeader("Content-Disposition", "inline");
            response.setContentLengthLong(served.sizeBytes());
            served.inputStream().transferTo(response.getOutputStream());
        }
    }

    @GetMapping("/policy")
    @Operation(operationId="getChatFilePolicy", summary="Get the active Chat file upload policy")
    @ApiResponse(responseCode="200",description="Active upload policy",useReturnTypeSchema=true)
    ChatFilePolicyResponse policy(@CurrentActor IdentityContext identity) {
        var policy = files.policy(identity.actorId());
        return new ChatFilePolicyResponse(policy.maxSizeBytes(), policy.deploymentCeilingBytes());
    }

    @PostMapping("/uploads")
    @Operation(operationId="initiateChatFileUpload", summary="Authorize or resume an owner-private, idempotent file upload")
    @ApiResponse(responseCode="200",description="Upload receipt and authorization when pending",useReturnTypeSchema=true)
    ChatFileUploadResponse upload(@CurrentActor IdentityContext identity,
            @Valid @RequestBody ChatFileUploadRequest request) {
        var result = files.initiate(identity.actorId(), new UserFileService.UploadInput(request.requestId(), request.filename(),
                request.mediaType(), request.sizeBytes(), request.sha256()));
        return new ChatFileUploadResponse(ChatFileResponse.from(result.file()), result.upload());
    }

    @PostMapping("/{fileId}/finalize")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId="finalizeChatFileUpload", summary="Verify upload integrity and queue asynchronous extraction")
    @ApiResponse(responseCode="202",description="File accepted for asynchronous processing",useReturnTypeSchema=true)
    ChatFileResponse finalizeUpload(@CurrentActor IdentityContext identity, @PathVariable UUID fileId) {
        return ChatFileResponse.from(files.finalizeUpload(identity.actorId(), fileId));
    }

    @GetMapping
    @Operation(operationId="listChatFiles", summary="List recent owner-private files")
    @ApiResponse(responseCode="200",description="Recent files",useReturnTypeSchema=true)
    List<ChatFileResponse> recent(@CurrentActor IdentityContext identity,
            @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="30") int limit) {
        var recent = files.recent(identity.actorId(), offset, limit);
        var ready = search.ready(identity.actorId(), recent.stream().map(UserFile::id).collect(java.util.stream.Collectors.toSet()));
        return recent.stream().map(file -> ChatFileResponse.from(file, ready.contains(file.id()))).toList();
    }

    @GetMapping("/{fileId}")
    @Operation(operationId="getChatFile", summary="Read file metadata and processing status")
    @ApiResponse(responseCode="200",description="File metadata",useReturnTypeSchema=true)
    ChatFileResponse get(@CurrentActor IdentityContext identity, @PathVariable UUID fileId) {
        return ChatFileResponse.from(files.get(identity.actorId(), fileId), search.ready(identity.actorId(), java.util.Set.of(fileId)).contains(fileId));
    }

    @PostMapping("/{fileId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId="retryChatFile", summary="Retry processing a failed owner-private file")
    @ApiResponse(responseCode="202",description="Retry queued",useReturnTypeSchema=true)
    ChatFileResponse retry(@CurrentActor IdentityContext identity, @PathVariable UUID fileId) {
        return ChatFileResponse.from(files.retry(identity.actorId(), fileId));
    }

    @DeleteMapping("/{fileId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId="deleteChatFile", summary="Make an owner-private file unavailable and queue cleanup")
    @ApiResponse(responseCode="202",description="File deletion accepted",useReturnTypeSchema=true)
    ChatFileResponse delete(@CurrentActor IdentityContext identity, @PathVariable UUID fileId) {
        return ChatFileResponse.from(files.delete(identity.actorId(), fileId));
    }
}
