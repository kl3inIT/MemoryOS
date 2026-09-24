package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ChatMessageResponse;
import io.memoryos.api.chat.contract.ChatSessionResponse;
import io.memoryos.api.chat.contract.ChatSessionSearchResponse;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSessionService;
import io.memoryos.chat.ChatWorkspaceService;
import io.memoryos.chat.image.ImageArtifactService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.MediaType;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid request or cursor",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Tenant membership or CSRF requirement not met",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Conversation or message not accessible",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatSessionController {
    private final ChatSessionService sessions;
    private final ChatWorkspaceService workspace;
    private final ImageArtifactService images;
    private final io.memoryos.chat.interpreter.InterpreterService interpreter;
    private final io.memoryos.chat.ChatTurnService turns;
    private final io.memoryos.chat.application.ChatBranchService branches;

    ChatSessionController(ChatSessionService sessions, ChatWorkspaceService workspace, ImageArtifactService images,
            io.memoryos.chat.interpreter.InterpreterService interpreter, io.memoryos.chat.ChatTurnService turns,
            io.memoryos.chat.application.ChatBranchService branches) {
        this.sessions = sessions; this.workspace = workspace; this.images = images; this.interpreter = interpreter;
        this.turns = turns; this.branches = branches;
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteAllChatSessions",
            summary = "Delete every owned conversation as deleting each one would, stopping active replies (Onyx Delete All Chats)")
    void deleteAll(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        turns.deleteAll(identity.actorId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createChatSession", summary = "Create a private chat session")
    @ApiResponse(responseCode = "201", description = "Created private session", useReturnTypeSchema = true)
    ChatSessionResponse create(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Valid @RequestBody CreateChatSession request) {
        return ChatSessionResponse.from(workspace.create(identity.actorId(), request.title(), request.personaId(),
                request.projectId(), Boolean.TRUE.equals(request.temporary())));
    }

    @GetMapping
    @Operation(operationId = "listChatSessions", summary = "List the actor's private chat sessions")
    @ApiResponse(responseCode = "200", description = "Owned sessions", useReturnTypeSchema = true)
    List<ChatSessionResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Parameter(description = "List the conversations the caller archived instead of the sidebar's")
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
        return sessions.list(identity.actorId(), archived, offset, limit).stream().map(ChatSessionResponse::from).toList();
    }

    @PostMapping("/{sessionId}/archive")
    @Operation(operationId = "archiveChatSession",
            summary = "Take one of the caller's conversations off the sidebar while keeping it")
    @ApiResponse(responseCode = "200", description = "The conversation as it is now archived", useReturnTypeSchema = true)
    ChatSessionResponse archive(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId) {
        return ChatSessionResponse.from(sessions.archive(identity.actorId(), sessionId, true));
    }

    @PostMapping("/{sessionId}/unarchive")
    @Operation(operationId = "unarchiveChatSession", summary = "Put an archived conversation back on the sidebar")
    @ApiResponse(responseCode = "200", description = "The conversation as it is now listed", useReturnTypeSchema = true)
    ChatSessionResponse unarchive(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId) {
        return ChatSessionResponse.from(sessions.archive(identity.actorId(), sessionId, false));
    }

    @Schema(name = "ChatSessionsArchived")
    record ArchivedResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int archived) {}

    @PostMapping("/archive-all")
    @Operation(operationId = "archiveAllChatSessions", summary = "Archive the caller's conversations in one command")
    @ApiResponse(responseCode = "200", description = "How many conversations were archived", useReturnTypeSchema = true)
    ArchivedResponse archiveAll(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return new ArchivedResponse(sessions.archiveAll(identity.actorId()));
    }

    @PostMapping("/{sessionId}/messages/{messageId}/branch")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "branchChatSession",
            summary = "Copy this conversation's selected path up to one message into a new conversation")
    @ApiResponse(responseCode = "201", description = "The new conversation", useReturnTypeSchema = true)
    ChatSessionResponse branch(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId, @PathVariable UUID messageId,
            @RequestBody(required = false) BranchChatSession request) {
        return ChatSessionResponse.from(branches.branch(identity.actorId(), sessionId, messageId,
                request == null ? null : request.title()));
    }

    @Schema(name = "BranchChatSessionRequest")
    record BranchChatSession(
            @Schema(description = "What to call the branch; the server names it after its origin when absent")
            @Size(max = 200) @Nullable String title) {}

    @GetMapping("/{sessionId}")
    @Operation(operationId = "getChatSession", summary = "Read an owned chat session")
    @ApiResponse(responseCode = "200", description = "Owned session", useReturnTypeSchema = true)
    ChatSessionResponse get(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId) {
        return ChatSessionResponse.from(sessions.get(identity.actorId(), sessionId));
    }

    @GetMapping("/search")
    @Operation(operationId = "searchChatSessions", summary = "Search owned conversation titles and all saved message versions")
    @ApiResponse(responseCode = "200", description = "Owned matching sessions; opening preserves the selected branch", useReturnTypeSchema = true)
    ChatSessionSearchResponse search(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        var results = sessions.search(identity.actorId(), query, offset, limit);
        return new ChatSessionSearchResponse(results.stream().limit(limit).map(ChatSessionSearchResponse.Item::from).toList(), results.size() > limit);
    }

    @GetMapping("/{sessionId}/messages")
    @Operation(operationId = "getChatHistory", summary = "Read the selected chat branch after a message cursor")
    @ApiResponse(responseCode = "200", description = "Selected branch history", useReturnTypeSchema = true)
    List<ChatMessageResponse> history(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId, @RequestParam(required = false) @Nullable UUID after,
            @RequestParam(defaultValue = "50") int limit) {
        var messages = sessions.history(identity.actorId(), sessionId, after, limit);
        var assistantIds = messages.stream()
                .filter(message -> message.role() == ChatMessage.Role.ASSISTANT).map(ChatMessage::id).toList();
        var byMessage = images.forMessages(identity.actorId(), assistantIds);
        var filesByMessage = interpreter.forMessages(identity.actorId(), assistantIds);
        return messages.stream().map(message -> ChatMessageResponse.from(message,
                byMessage.getOrDefault(message.id(), List.of()).stream()
                        .map(artifact -> new ChatMessageResponse.ImageRef(artifact.id(), artifact.mediaType(),
                                artifact.revisedPrompt(), artifact.deleted())).toList(),
                filesByMessage.getOrDefault(message.id(), List.of()).stream()
                        .map(file -> new ChatMessageResponse.GeneratedFileRef(file.id(), file.filename(), file.mediaType(),
                                file.sizeBytes(), file.chart(), file.deleted())).toList())).toList();
    }

    record CreateChatSession(@NotBlank @Size(max = 200) String title, @Nullable UUID personaId,
            @Nullable UUID projectId,
            @Schema(description = "Leave no history: listed nowhere, deleted with its uploads after its window")
            @Nullable Boolean temporary) {}
}
