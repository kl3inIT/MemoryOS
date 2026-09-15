package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ChatMessageResponse;
import io.memoryos.api.chat.contract.ChatSessionResponse;
import io.memoryos.api.chat.contract.ChatSessionSearchResponse;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSessionService;
import io.memoryos.chat.ChatWorkspaceService;
import io.memoryos.chat.image.ImageArtifactService;
import io.memoryos.iam.identity.IdentityContext;
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

    ChatSessionController(ChatSessionService sessions, ChatWorkspaceService workspace, ImageArtifactService images) {
        this.sessions = sessions; this.workspace = workspace; this.images = images;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createChatSession", summary = "Create a private chat session")
    @ApiResponse(responseCode = "201", description = "Created private session", useReturnTypeSchema = true)
    ChatSessionResponse create(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Valid @RequestBody CreateChatSession request) {
        return ChatSessionResponse.from(workspace.create(identity.actorId(), request.title(), request.personaId(), request.projectId()));
    }

    @GetMapping
    @Operation(operationId = "listChatSessions", summary = "List the actor's private chat sessions")
    @ApiResponse(responseCode = "200", description = "Owned sessions", useReturnTypeSchema = true)
    List<ChatSessionResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
        return sessions.list(identity.actorId(), offset, limit).stream().map(ChatSessionResponse::from).toList();
    }

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
        var byMessage = images.forMessages(identity.actorId(), messages.stream()
                .filter(message -> message.role() == ChatMessage.Role.ASSISTANT).map(ChatMessage::id).toList());
        return messages.stream().map(message -> ChatMessageResponse.from(message,
                byMessage.getOrDefault(message.id(), List.of()).stream()
                        .map(artifact -> new ChatMessageResponse.ImageRef(artifact.id(), artifact.mediaType(), artifact.revisedPrompt())).toList())).toList();
    }

    record CreateChatSession(@NotBlank @Size(max = 200) String title, @Nullable UUID personaId, @Nullable UUID projectId) {}
}
