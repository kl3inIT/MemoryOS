package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ChatSessionResponse;
import io.memoryos.chat.ChatProjectService;
import io.memoryos.chat.ChatProjectService.ProjectInput;
import io.memoryos.chat.ChatProjectService.ProjectView;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/chat/projects", produces = MediaType.APPLICATION_JSON_VALUE)
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
class ChatProjectController {
    private final ChatProjectService projects;
    ChatProjectController(ChatProjectService projects) { this.projects = projects; }
    @GetMapping
    @Operation(operationId = "listChatProjects", summary = "List actor-owned projects")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<ProjectView> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
        return projects.list(identity.actorId(), offset, limit);
    }
    @GetMapping("/{projectId}")
    @Operation(operationId = "getChatProject", summary = "Read an owned project")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    ProjectView get(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID projectId) {
        return projects.get(identity.actorId(), projectId);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createChatProject", summary = "Create a private project")
    @ApiResponse(responseCode = "201", description = "Successful chat operation", useReturnTypeSchema = true)
    ProjectView create(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @RequestBody ProjectInput request) {
        return projects.create(identity.actorId(), request);
    }
    @PutMapping("/{projectId}")
    @Operation(operationId = "updateChatProject", summary = "Update project instructions with an expected revision")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    ProjectView update(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID projectId,
            @RequestParam long revision, @RequestBody ProjectInput request) {
        return projects.update(identity.actorId(), projectId, revision, request);
    }
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteChatProject", summary = "Delete a project and retain its conversations")
    void delete(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID projectId, @RequestParam long revision) {
        projects.delete(identity.actorId(), projectId, revision);
    }
    @GetMapping("/{projectId}/sessions")
    @Operation(operationId = "listProjectChatSessions", summary = "List owned conversations in an owned project")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<ChatSessionResponse> conversations(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID projectId, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
        return projects.conversations(identity.actorId(), projectId, offset, limit).stream().map(ChatSessionResponse::from).toList();
    }
    @PostMapping("/{projectId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createProjectChatSession", summary = "Create a conversation inside an owned project")
    @ApiResponse(responseCode = "201", description = "Successful chat operation", useReturnTypeSchema = true)
    ChatSessionResponse conversation(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID projectId, @Valid @RequestBody ProjectConversation request) {
        return ChatSessionResponse.from(projects.createConversation(identity.actorId(), projectId, request.title()));
    }
    record ProjectConversation(@NotBlank @Size(max = 200) String title) {}
}
