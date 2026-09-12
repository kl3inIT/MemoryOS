package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ChatFilePolicyResponse;
import io.memoryos.api.chat.contract.ChatFileResponse;
import io.memoryos.api.chat.contract.ChatFileUploadRequest;
import io.memoryos.api.chat.contract.ChatFileUploadResponse;
import io.memoryos.chat.ChatFileService;
import io.memoryos.chat.ChatFileContentService;
import io.memoryos.api.chat.contract.ChatFileTextResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/chat/files", produces=MediaType.APPLICATION_JSON_VALUE)
@Tag(name="Chat")
@ApiResponse(responseCode="400", description="Invalid file request", content=@Content(mediaType=MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema=@Schema(ref="#/components/schemas/ApiProblem")))
@ApiResponse(responseCode="403", description="Tenant membership or CSRF requirement not met", content=@Content(mediaType=MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema=@Schema(ref="#/components/schemas/ApiProblem")))
@ApiResponse(responseCode="404", description="File not accessible", content=@Content(mediaType=MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema=@Schema(ref="#/components/schemas/ApiProblem")))
@ApiResponse(responseCode="409", description="File state or request identity conflict", content=@Content(mediaType=MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema=@Schema(ref="#/components/schemas/ApiProblem")))
@ApiResponse(responseCode="503", description="Storage unavailable", content=@Content(mediaType=MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema=@Schema(ref="#/components/schemas/ApiProblem")))
@ApiResponse(responseCode="401", description="Authentication required", content=@Content)
@SecurityRequirement(name="browserSession")
@SecurityRequirement(name="bearerAuth")
class ChatFileController {
    private final ChatFileService files;
    private final ChatFileContentService content;
    private final io.memoryos.chat.ChatFileSearchService search;
    ChatFileController(ChatFileService files, ChatFileContentService content, io.memoryos.chat.ChatFileSearchService search) {
        this.files = files; this.content = content; this.search = search;
    }

    @GetMapping("/{fileId}/text")
    @Operation(operationId="readChatFileText", summary="Read a bounded window of owner-private extracted text")
    @ApiResponse(responseCode="200",description="Extracted text window",useReturnTypeSchema=true)
    ResponseEntity<ChatFileTextResponse> text(@Parameter(hidden=true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID fileId, @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="16000") int count) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ChatFileTextResponse.from(files.read(identity.actorId(), fileId, offset, count)));
    }

    @GetMapping("/{fileId}/passages")
    @Operation(operationId="readChatFilePassages", summary="Read current owner-private indexed file passages at a cited position")
    @ApiResponse(responseCode="200",description="Authorized file passages",useReturnTypeSchema=true)
    ResponseEntity<io.memoryos.retrieval.SearchDocument> passages(@Parameter(hidden=true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID fileId, @RequestParam UUID generation, @RequestParam(defaultValue="0") int from) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(search.read(identity.actorId(), fileId, generation, from));
    }

    @GetMapping(value="/{fileId}/content", produces=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(operationId="downloadChatFile", summary="Download an owner-private original file without inline execution")
    @ApiResponse(responseCode="200",description="Original file bytes",content=@Content(mediaType=MediaType.APPLICATION_OCTET_STREAM_VALUE, schema=@Schema(type="string",format="binary")))
    void download(@Parameter(hidden=true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID fileId, HttpServletResponse response) throws IOException {
        var file = files.get(identity.actorId(), fileId);
        try (var input = content.open(identity.actorId(), fileId)) {
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
            response.setHeader("Content-Disposition", ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString());
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setContentLengthLong(file.sizeBytes());
            input.inputStream().transferTo(response.getOutputStream());
        }
    }

    @GetMapping("/policy")
    @Operation(operationId="getChatFilePolicy", summary="Get the active Chat file upload policy")
    @ApiResponse(responseCode="200",description="Active upload policy",useReturnTypeSchema=true)
    ChatFilePolicyResponse policy(@Parameter(hidden=true) @AuthenticationPrincipal IdentityContext identity) {
        var policy = files.policy(identity.actorId());
        return new ChatFilePolicyResponse(policy.maxSizeBytes(), policy.deploymentCeilingBytes());
    }

    @PostMapping("/uploads")
    @Operation(operationId="initiateChatFileUpload", summary="Authorize or resume an owner-private, idempotent file upload")
    @ApiResponse(responseCode="200",description="Upload receipt and authorization when pending",useReturnTypeSchema=true)
    ChatFileUploadResponse upload(@Parameter(hidden=true) @AuthenticationPrincipal IdentityContext identity,
            @Valid @RequestBody ChatFileUploadRequest request) {
        var result = files.initiate(identity.actorId(), new ChatFileService.UploadInput(request.requestId(), request.filename(),
                request.mediaType(), request.sizeBytes(), request.sha256()));
        return new ChatFileUploadResponse(ChatFileResponse.from(result.file()), result.upload());
    }

    @PostMapping("/{fileId}/finalize")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId="finalizeChatFileUpload", summary="Verify upload integrity and queue asynchronous extraction")
    @ApiResponse(responseCode="202",description="File accepted for asynchronous processing",useReturnTypeSchema=true)
    ChatFileResponse finalizeUpload(@Parameter(hidden=true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID fileId) {
        return ChatFileResponse.from(files.finalizeUpload(identity.actorId(), fileId));
    }

    @GetMapping
    @Operation(operationId="listChatFiles", summary="List recent owner-private files")
    @ApiResponse(responseCode="200",description="Recent files",useReturnTypeSchema=true)
    List<ChatFileResponse> recent(@Parameter(hidden=true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="30") int limit) {
        var recent = files.recent(identity.actorId(), offset, limit);
        var ready = search.ready(identity.actorId(), recent.stream().map(io.memoryos.chat.UserFile::id).collect(java.util.stream.Collectors.toSet()));
        return recent.stream().map(file -> ChatFileResponse.from(file, ready.contains(file.id()))).toList();
    }

    @GetMapping("/{fileId}")
    @Operation(operationId="getChatFile", summary="Read file metadata and processing status")
    @ApiResponse(responseCode="200",description="File metadata",useReturnTypeSchema=true)
    ChatFileResponse get(@Parameter(hidden=true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID fileId) {
        return ChatFileResponse.from(files.get(identity.actorId(), fileId), search.ready(identity.actorId(), java.util.Set.of(fileId)).contains(fileId));
    }

    @PostMapping("/{fileId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId="retryChatFile", summary="Retry processing a failed owner-private file")
    @ApiResponse(responseCode="202",description="Retry queued",useReturnTypeSchema=true)
    ChatFileResponse retry(@Parameter(hidden=true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID fileId) {
        return ChatFileResponse.from(files.retry(identity.actorId(), fileId));
    }

    @DeleteMapping("/{fileId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId="deleteChatFile", summary="Make an owner-private file unavailable and queue cleanup")
    @ApiResponse(responseCode="202",description="File deletion accepted",useReturnTypeSchema=true)
    ChatFileResponse delete(@Parameter(hidden=true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID fileId) {
        return ChatFileResponse.from(files.delete(identity.actorId(), fileId));
    }
}
