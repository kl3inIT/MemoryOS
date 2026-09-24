package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ChatFileResponse;
import io.memoryos.api.chat.contract.ChatLibraryArchiveFileRequest;
import io.memoryos.api.chat.contract.ChatLibraryArchiveRequest;
import io.memoryos.api.chat.contract.ChatLibraryArchiveResponse;
import io.memoryos.api.chat.contract.ChatLibraryCategoryUsageResponse;
import io.memoryos.api.chat.contract.ChatLibraryContentMatchResponse;
import io.memoryos.api.chat.contract.ChatLibraryFileChangeRequest;
import io.memoryos.api.chat.contract.ChatLibraryFileResponse;
import io.memoryos.api.chat.contract.ChatLibraryPageResponse;
import io.memoryos.api.chat.contract.ChatLibraryPassageResponse;
import io.memoryos.api.chat.contract.ChatLibraryTrashEmptiedResponse;
import io.memoryos.api.chat.contract.ChatLibraryTrashWindowResponse;
import io.memoryos.api.chat.contract.ChatLibraryUsageResponse;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatLibraryArchiveItem;
import io.memoryos.chat.ChatLibraryFile;
import io.memoryos.chat.ChatLibraryService;
import io.memoryos.chat.application.ChatLibraryArchiveService;
import io.memoryos.iam.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat/library", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid library request", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Tenant membership or CSRF requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Chat is unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "503", description = "Storage unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatLibraryController {
    private final ChatLibraryService library;
    private final ChatLibraryArchiveService archives;
    private final io.memoryos.chat.ChatStorageQuotaService quotas;
    private final io.memoryos.chat.ChatLibraryTrashService trash;

    ChatLibraryController(ChatLibraryService library, ChatLibraryArchiveService archives,
            io.memoryos.chat.ChatStorageQuotaService quotas, io.memoryos.chat.ChatLibraryTrashService trash) {
        this.library = library; this.archives = archives; this.quotas = quotas; this.trash = trash;
    }

    @GetMapping
    @Operation(operationId = "listChatLibrary",
            summary = "List the caller's own uploads, generated files and generated images as one paginated library,"
                    + " optionally narrowed to one conversation, to starred files, or to uploads still in progress")
    @ApiResponse(responseCode = "200", description = "A page of the caller's files", useReturnTypeSchema = true)
    ResponseEntity<ChatLibraryPageResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue = "") String query,
            @Parameter(description = "Empty means every source") @RequestParam(required = false) @Nullable List<String> sources,
            @Parameter(description = "Empty means every category") @RequestParam(required = false) @Nullable List<String> categories,
            @Parameter(description = "Only this conversation's own files; the caller must own it")
            @RequestParam(required = false) @Nullable UUID sessionId,
            @Parameter(description = "Only starred files") @RequestParam(defaultValue = "false") boolean favorite,
            @Parameter(description = "READY lists usable files; PENDING lists uploads still uploading, processing or"
                    + " failed; TRASH lists what the owner deleted and may still restore",
                    schema = @Schema(allowableValues = {"READY", "PENDING", "TRASH"}))
            @RequestParam(defaultValue = "READY") String status,
            @RequestParam(defaultValue = "NEWEST") String sort,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        String view = status.toUpperCase(Locale.ROOT);
        if (!List.of("READY", "PENDING", "TRASH").contains(view)) throw ChatException.invalid("Unknown status.");
        var page = library.list(identity.actorId(), new ChatLibraryService.Listing(query,
                parse(sources, ChatLibraryFile.Source.class), parse(categories, ChatLibraryFile.Category.class),
                sessionId, favorite, "PENDING".equals(view), "TRASH".equals(view),
                value(sort, ChatLibraryFile.Sort.class), offset, limit));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new ChatLibraryPageResponse(
                page.items().stream().map(ChatLibraryFileResponse::from).toList(),
                page.totalCount(), page.totalBytes(), page.hasMore()));
    }

    @PatchMapping(value = "/{source}/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "changeChatLibraryFile", summary = "Rename or star one of the caller's files")
    @ApiResponse(responseCode = "200", description = "The file as the library now lists it", useReturnTypeSchema = true)
    ResponseEntity<ChatLibraryFileResponse> change(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Parameter(schema = @Schema(allowableValues = {"UPLOAD", "GENERATED", "IMAGE"})) @PathVariable String source,
            @PathVariable UUID id, @RequestBody ChatLibraryFileChangeRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ChatLibraryFileResponse.from(library.update(
                identity.actorId(), value(source, ChatLibraryFile.Source.class), id, request.filename(), request.favorite())));
    }

    @GetMapping("/search")
    @Operation(operationId = "searchChatLibraryContent",
            summary = "Find the caller's own indexed uploads by what they contain, with the matching passages")
    @ApiResponse(responseCode = "200", description = "Matching files, best first", useReturnTypeSchema = true)
    ResponseEntity<List<ChatLibraryContentMatchResponse>> search(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam String query) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(library.searchContent(identity.actorId(), query)
                .stream().map(match -> new ChatLibraryContentMatchResponse(ChatLibraryFileResponse.from(match.file()),
                        match.passages().stream().map(passage -> new ChatLibraryPassageResponse(passage.text(), passage.ordinal())).toList()))
                .toList());
    }

    @PostMapping("/{source}/{id}/copy")
    @Operation(operationId = "copyChatLibraryFile",
            summary = "Copy a generated file or image into an upload of the caller, so it can be attached to a message,"
                    + " a Project or an assistant; asking again returns the same upload")
    @ApiResponse(responseCode = "200", description = "The upload holding the copy; it is PROCESSING until extracted",
            useReturnTypeSchema = true)
    ResponseEntity<ChatFileResponse> copy(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Parameter(schema = @Schema(allowableValues = {"GENERATED", "IMAGE"})) @PathVariable String source,
            @PathVariable UUID id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ChatFileResponse.from(
                library.copy(identity.actorId(), value(source, ChatLibraryFile.Source.class), id)));
    }

    @PostMapping(value = "/archives", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "requestChatLibraryArchive",
            summary = "Ask for a ZIP of the selected files; a worker packs it and its owner downloads it until it expires")
    @ApiResponse(responseCode = "202", description = "The archive request as recorded", useReturnTypeSchema = true)
    ChatLibraryArchiveResponse requestArchive(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestBody ChatLibraryArchiveRequest request) {
        var files = (request.files() == null ? List.<ChatLibraryArchiveFileRequest>of() : request.files()).stream()
                .map(file -> new ChatLibraryArchiveItem(
                        value(file.source(), ChatLibraryFile.Source.class), file.id()))
                .toList();
        return ChatLibraryArchiveResponse.from(archives.request(identity.actorId(), files));
    }

    @GetMapping("/archives")
    @Operation(operationId = "listChatLibraryArchives", summary = "The caller's own archives that have not expired")
    @ApiResponse(responseCode = "200", description = "Archives, newest first", useReturnTypeSchema = true)
    ResponseEntity<List<ChatLibraryArchiveResponse>> listArchives(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(archives.list(identity.actorId()).stream().map(ChatLibraryArchiveResponse::from).toList());
    }

    @GetMapping("/archives/{archiveId}")
    @Operation(operationId = "getChatLibraryArchive", summary = "One archive of the caller, with its status")
    @ApiResponse(responseCode = "200", description = "The archive", useReturnTypeSchema = true)
    ResponseEntity<ChatLibraryArchiveResponse> getArchive(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID archiveId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(ChatLibraryArchiveResponse.from(archives.get(identity.actorId(), archiveId)));
    }

    @GetMapping(value = "/archives/{archiveId}/content", produces = "application/zip")
    @Operation(operationId = "downloadChatLibraryArchive", summary = "Download the caller's own archive while it lives")
    @ApiResponse(responseCode = "200", description = "The ZIP bytes",
            content = @Content(mediaType = "application/zip", schema = @Schema(type = "string", format = "binary")))
    void downloadArchive(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID archiveId, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        var download = archives.open(identity.actorId(), archiveId);
        try (var content = download.content()) {
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", org.springframework.http.ContentDisposition.attachment()
                    .filename(download.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString());
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setContentLengthLong(content.metadata().sizeBytes());
            content.inputStream().transferTo(response.getOutputStream());
        }
    }

    @GetMapping("/usage")
    @Operation(operationId = "getChatLibraryUsage",
            summary = "What the caller's file library holds and the storage limit that applies to them")
    @ApiResponse(responseCode = "200", description = "Used bytes, file count, the limit and the breakdown",
            useReturnTypeSchema = true)
    ResponseEntity<ChatLibraryUsageResponse> usage(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        var usage = quotas.usage(identity.actorId());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new ChatLibraryUsageResponse(usage.usedBytes(),
                usage.fileCount(), usage.limitBytes(),
                usage.byCategory().entrySet().stream()
                        .map(entry -> new ChatLibraryCategoryUsageResponse(entry.getKey().name(), entry.getValue()))
                        .sorted(java.util.Comparator.comparing(ChatLibraryCategoryUsageResponse::category)).toList()));
    }

    @GetMapping("/trash")
    @Operation(operationId = "getChatLibraryTrashWindow",
            summary = "How long a deleted file stays in the trash in this deployment")
    @ApiResponse(responseCode = "200", description = "The trash window", useReturnTypeSchema = true)
    ResponseEntity<ChatLibraryTrashWindowResponse> trashWindow(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new ChatLibraryTrashWindowResponse(trash.window().toDays()));
    }

    @PostMapping("/{source}/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "restoreChatLibraryFile",
            summary = "Take one of the caller's files out of the trash while its bytes are still there")
    @ApiResponse(responseCode = "204", description = "Restored", content = @Content)
    void restore(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Parameter(schema = @Schema(allowableValues = {"UPLOAD", "GENERATED", "IMAGE"})) @PathVariable String source,
            @PathVariable UUID id) {
        trash.restore(identity.actorId(), value(source, ChatLibraryFile.Source.class), id);
    }

    @PostMapping("/{source}/{id}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "purgeChatLibraryFile",
            summary = "End the trash window of one file now, so its bytes are released")
    @ApiResponse(responseCode = "204", description = "The file is queued for release", content = @Content)
    void purge(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Parameter(schema = @Schema(allowableValues = {"UPLOAD", "GENERATED", "IMAGE"})) @PathVariable String source,
            @PathVariable UUID id) {
        trash.purge(identity.actorId(), value(source, ChatLibraryFile.Source.class), id);
    }

    @PostMapping("/trash/empty")
    @Operation(operationId = "emptyChatLibraryTrash", summary = "End the trash window of everything the caller deleted")
    @ApiResponse(responseCode = "200", description = "How many files were queued for release", useReturnTypeSchema = true)
    ResponseEntity<ChatLibraryTrashEmptiedResponse> emptyTrash(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new ChatLibraryTrashEmptiedResponse(trash.empty(identity.actorId())));
    }

    private static <E extends Enum<E>> Set<E> parse(@Nullable List<String> values, Class<E> type) {
        if (values == null) return Set.of();
        var parsed = new LinkedHashSet<E>();
        values.stream().flatMap(value -> Arrays.stream(value.split(","))).map(String::trim)
                .filter(value -> !value.isEmpty()).forEach(value -> parsed.add(value(value, type)));
        return parsed;
    }

    private static <E extends Enum<E>> E value(String value, Class<E> type) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw ChatException.invalid("Unknown " + type.getSimpleName().toLowerCase(Locale.ROOT) + ".");
        }
    }
}
