package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ChatBranchSelectionRequest;
import io.memoryos.api.chat.contract.ChatPersonaSelectionRequest;
import io.memoryos.api.chat.contract.ChatProjectSelectionRequest;
import io.memoryos.api.chat.contract.ChatReasoningSelectionRequest;
import io.memoryos.api.chat.contract.ChatSessionSettingsRequest;
import io.memoryos.api.chat.contract.ChatSessionTitleRequest;
import io.memoryos.api.chat.contract.ChatSessionResponse;
import io.memoryos.chat.ChatBranch;
import io.memoryos.chat.ChatSessionService;
import io.memoryos.chat.ChatWorkspaceService;
import io.memoryos.chat.ChatTurnService;
import io.memoryos.chat.ChatPersonaService;
import io.memoryos.chat.ChatProjectService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/chat/sessions/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid chat request",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Tenant membership or CSRF requirement not met",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Chat resource not accessible",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "409", description = "Conversation is running or revision has changed",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatSessionEditorController {
    private final ChatSessionService sessions;
    private final ChatTurnService turns;
    private final ChatPersonaService personas;
    private final ChatProjectService projects;
    private final ChatWorkspaceService workspace;
    ChatSessionEditorController(ChatSessionService sessions, ChatTurnService turns, ChatPersonaService personas, ChatProjectService projects, ChatWorkspaceService workspace) {
        this.sessions = sessions; this.turns = turns; this.personas = personas; this.projects = projects;
        this.workspace = workspace;
    }
    @PutMapping("/title")
    @Operation(operationId = "renameChatSession", summary = "Rename an owned conversation")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    ChatSessionResponse rename(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId, @Valid @RequestBody ChatSessionTitleRequest request) {
        return ChatSessionResponse.from(sessions.rename(identity.actorId(), sessionId, request.title()));
    }
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteChatSession", summary = "Delete an owned conversation and stop its active reply")
    void delete(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sessionId) {
        turns.delete(identity.actorId(), sessionId);
    }

    @PostMapping("/title")
    @Operation(operationId = "generateChatTitle", summary = "Generate a short owned conversation title once, preserving manual renames")
    @ApiResponse(responseCode = "200", description = "Current title, including fallback when naming is unavailable", useReturnTypeSchema = true)
    ChatSessionResponse generateTitle(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sessionId) {
        turns.generateTitle(identity.actorId(), sessionId);
        return ChatSessionResponse.from(sessions.get(identity.actorId(), sessionId));
    }
    @GetMapping("/branches")
    @Operation(operationId = "getChatBranches", summary = "Read version relationships in an owned conversation")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<ChatBranch> branches(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sessionId) {
        return sessions.branches(identity.actorId(), sessionId);
    }
    @PutMapping("/branch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "selectChatBranch", summary = "Select a message version without generating a reply")
    void branch(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId, @Valid @RequestBody ChatBranchSelectionRequest request) {
        sessions.selectBranch(identity.actorId(), sessionId, request.messageId(), request.expectedChildId());
    }
    @PutMapping("/persona")
    @Operation(operationId = "selectChatPersona", summary = "Choose an authorized assistant for subsequent turns")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    ChatSessionResponse persona(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId, @Valid @RequestBody ChatPersonaSelectionRequest request) {
        return ChatSessionResponse.from(personas.select(identity.actorId(), sessionId, request.personaId()));
    }
    @PutMapping("/project")
    @Operation(operationId = "moveChatProject", summary = "Move an owned conversation into or out of an owned project")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    ChatSessionResponse project(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId, @RequestBody ChatProjectSelectionRequest request) {
        return ChatSessionResponse.from(projects.move(identity.actorId(), sessionId, request.projectId()));
    }
    @PutMapping("/reasoning")
    @Operation(operationId = "pinChatReasoningEffort",
            summary = "Pin how much this conversation's model should think, or clear the choice")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    ChatSessionResponse reasoning(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId, @RequestBody ChatReasoningSelectionRequest request) {
        return ChatSessionResponse.from(sessions.pinReasoningEffort(identity.actorId(), sessionId, request.reasoningEffort()));
    }

    @PutMapping("/settings")
    @Operation(operationId = "configureChatSession", summary = "Atomically change assistant and project for subsequent turns")
    @ApiResponse(responseCode = "200", description = "Updated conversation settings", useReturnTypeSchema = true)
    ChatSessionResponse configure(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID sessionId, @Valid @RequestBody ChatSessionSettingsRequest request) {
        return ChatSessionResponse.from(workspace.configure(identity.actorId(), sessionId, request.personaId(), request.projectId()));
    }
}
