package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ChatMessageResponse;
import io.memoryos.api.chat.contract.ChatSessionResponse;
import io.memoryos.chat.ChatSessionService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/api/chat/sessions")
@Tag(name = "Chat")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatSessionController {
    private final ChatSessionService sessions;

    ChatSessionController(ChatSessionService sessions) { this.sessions = sessions; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createChatSession", summary = "Create a private chat session")
    ChatSessionResponse create(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Valid @RequestBody CreateChatSession request) {
        return ChatSessionResponse.from(sessions.create(identity.actorId(), request.title()));
    }

    @GetMapping
    @Operation(operationId = "listChatSessions", summary = "List the actor's private chat sessions")
    List<ChatSessionResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
        return sessions.list(identity.actorId(), offset, limit).stream().map(ChatSessionResponse::from).toList();
    }

    @GetMapping("/{sessionId}")
    @Operation(operationId = "getChatSession", summary = "Read an owned chat session")
    ChatSessionResponse get(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId) {
        return ChatSessionResponse.from(sessions.get(identity.actorId(), sessionId));
    }

    @GetMapping("/{sessionId}/messages")
    @Operation(operationId = "getChatHistory", summary = "Read the selected chat branch after a message cursor")
    List<ChatMessageResponse> history(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId, @RequestParam(required = false) @Nullable UUID after,
            @RequestParam(defaultValue = "50") int limit) {
        return sessions.history(identity.actorId(), sessionId, after, limit).stream().map(ChatMessageResponse::from).toList();
    }

    record CreateChatSession(@NotBlank @Size(max = 200) String title) {}
}
