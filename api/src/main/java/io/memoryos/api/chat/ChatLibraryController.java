package io.memoryos.api.chat;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatLibraryFile;
import io.memoryos.chat.ChatLibraryService;
import io.memoryos.iam.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat/library", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid library request", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Tenant membership or CSRF requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Chat is unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatLibraryController {
    private final ChatLibraryService library;

    ChatLibraryController(ChatLibraryService library) { this.library = library; }

    @Schema(name = "ChatLibraryPage")
    record LibraryPageResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<LibraryFileResponse> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Files matching the filter, not only this page") long totalCount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Total size of the files matching the filter") long totalBytes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) {}

    @Schema(name = "ChatLibraryFile")
    record LibraryFileResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"UPLOAD", "GENERATED", "IMAGE"}) String source,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String filename,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mediaType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sizeBytes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"DOCUMENT", "SPREADSHEET", "IMAGE", "PRESENTATION", "OTHER"}) String category,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid",
                    description = "The conversation that produced the file; null for an upload") @Nullable UUID sessionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String sessionTitle,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Projects and assistants holding this file") List<UsageResponse> usedBy,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "False while a project or assistant holds the file") boolean deletable) {

        static LibraryFileResponse from(ChatLibraryFile file) {
            return new LibraryFileResponse(file.source().name(), file.id(), file.filename(), file.mediaType(),
                    file.sizeBytes(), file.createdAt(), file.category().name(), file.sessionId(), file.sessionTitle(),
                    file.usedBy().stream().map(UsageResponse::from).toList(), file.deletable());
        }
    }

    @Schema(name = "ChatLibraryFileUsage")
    record UsageResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"AGENT", "PROJECT"}) String kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {

        static UsageResponse from(ChatLibraryFile.Usage usage) {
            return new UsageResponse(usage.kind().name(), usage.id(), usage.name());
        }
    }

    @GetMapping
    @Operation(operationId = "listChatLibrary",
            summary = "List the caller's own uploads, generated files and generated images as one paginated library")
    @ApiResponse(responseCode = "200", description = "A page of the caller's files", useReturnTypeSchema = true)
    ResponseEntity<LibraryPageResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue = "") String query,
            @Parameter(description = "Empty means every source") @RequestParam(required = false) @Nullable List<String> sources,
            @Parameter(description = "Empty means every category") @RequestParam(required = false) @Nullable List<String> categories,
            @RequestParam(defaultValue = "NEWEST") String sort,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        var page = library.list(identity.actorId(), query,
                parse(sources, ChatLibraryFile.Source.class), parse(categories, ChatLibraryFile.Category.class),
                value(sort, ChatLibraryFile.Sort.class), offset, limit);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new LibraryPageResponse(
                page.items().stream().map(LibraryFileResponse::from).toList(),
                page.totalCount(), page.totalBytes(), page.hasMore()));
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
