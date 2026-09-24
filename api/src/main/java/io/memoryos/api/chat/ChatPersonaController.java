package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.AvailableChatModelResponse;
import io.memoryos.api.chat.contract.ChatAgentRefResponse;
import io.memoryos.api.chat.contract.ChatAgentShareOptionsResponse;
import io.memoryos.api.chat.contract.ChatPersonaLabelRequest;
import io.memoryos.api.chat.contract.ChatPersonaPinsRequest;
import io.memoryos.chat.AgentListFilter;
import io.memoryos.chat.ChatPersonaService.ListingInput;
import io.memoryos.chat.ChatPersonaService.PersonaInput;
import io.memoryos.chat.ChatPersonaService.PersonaView;
import io.memoryos.chat.ChatPersonaService.SharingInput;
import io.memoryos.chat.ChatPersonaService.TransferInput;
import io.memoryos.chat.ChatPersonaService;
import io.memoryos.chat.ChatModelAccess;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.iam.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
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
class ChatPersonaController {
    private final ChatPersonaService personas;
    private final ChatModelAccess models;
    private final SourceSearchService sources;
    ChatPersonaController(ChatPersonaService personas, ChatModelAccess models, SourceSearchService sources) {
        this.personas = personas; this.models = models; this.sources = sources;
    }


    @GetMapping("/personas")
    @Operation(operationId = "listChatPersonas", summary = "List usable agents: all listed, the actor's own, or shared with the actor")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<PersonaView> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue = "ALL") AgentListFilter view, @RequestParam(required = false) @Nullable UUID labelId,
            @RequestParam(required = false) @Nullable String q,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
        return personas.list(identity.actorId(), view, labelId, q, offset, limit);
    }
    @GetMapping("/personas/administration")
    @Operation(operationId = "listChatPersonasForAdministration", summary = "List every agent including unlisted, vacant and deleted ones; requires AGENTS_MANAGE")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<PersonaView> administration(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        return personas.administration(identity.actorId(), includeDeleted, offset, limit);
    }
    @GetMapping("/personas/{personaId}")
    @Operation(operationId = "getChatPersona", summary = "Read the full snapshot of a usable agent")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    PersonaView get(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId) {
        return personas.get(identity.actorId(), personaId);
    }
    @PostMapping("/personas")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createChatPersona", summary = "Create a private agent; requires AGENTS_CREATE")
    @ApiResponse(responseCode = "201", description = "Successful chat operation", useReturnTypeSchema = true)
    PersonaView create(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @RequestBody PersonaInput request) {
        return personas.create(identity.actorId(), request);
    }
    @PutMapping("/personas/{personaId}")
    @Operation(operationId = "updateChatPersona", summary = "Update agent settings with an expected revision")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    PersonaView update(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId,
            @RequestParam long revision, @RequestBody PersonaInput request) {
        return personas.update(identity.actorId(), personaId, revision, request);
    }
    @DeleteMapping("/personas/{personaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteChatPersona", summary = "Delete an agent while retaining conversation history")
    void delete(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId, @RequestParam long revision) {
        personas.delete(identity.actorId(), personaId, revision);
    }
    @PostMapping("/personas/{personaId}/restore")
    @Operation(operationId = "restoreChatPersona", summary = "Restore a deleted agent; requires AGENTS_MANAGE")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    PersonaView restore(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId) {
        return personas.restore(identity.actorId(), personaId);
    }
    @PutMapping("/personas/{personaId}/sharing")
    @Operation(operationId = "shareChatPersona", summary = "Replace people and Group shares and, for owners, Tenant-wide visibility")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    PersonaView share(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId,
            @RequestParam long revision, @RequestBody SharingInput request) {
        return personas.share(identity.actorId(), personaId, revision, request);
    }
    @DeleteMapping("/personas/{personaId}/sharing/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "leaveChatPersona", summary = "Remove the actor's direct share of an agent")
    void leave(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId) {
        personas.leave(identity.actorId(), personaId);
    }
    @PostMapping("/personas/{personaId}/owner")
    @Operation(operationId = "transferChatPersona", summary = "Transfer agent ownership to an active member or a Group")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    PersonaView transfer(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId,
            @RequestParam long revision, @RequestBody TransferInput request) {
        return personas.transfer(identity.actorId(), personaId, revision, request);
    }
    @PutMapping("/personas/{personaId}/listing")
    @Operation(operationId = "setChatPersonaListing", summary = "Set listed, featured and display priority; requires AGENTS_MANAGE")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    PersonaView listing(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId,
            @RequestParam long revision, @RequestBody ListingInput request) {
        return personas.listing(identity.actorId(), personaId, revision, request);
    }
    @PutMapping("/persona-order")
    @Operation(operationId = "reorderChatPersonas", summary = "Set display priorities from one ordered list; requires AGENTS_MANAGE")
    @ApiResponse(responseCode = "204", description = "Order saved")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void reorder(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @RequestBody ChatPersonaPinsRequest request) {
        personas.reorder(identity.actorId(), request.personaIds());
    }
    @GetMapping(value = "/personas/{personaId}/avatar", produces = {"image/png", "image/jpeg", "image/webp", "image/gif"})
    @Operation(operationId = "getChatPersonaAvatar", summary = "Read the avatar image of a usable agent")
    @ApiResponse(responseCode = "200", description = "Avatar image bytes", content = @Content(schema = @Schema(type = "string", format = "binary")))
    void avatar(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId,
            HttpServletResponse response) throws IOException {
        var avatar = personas.avatar(identity.actorId(), personaId);
        try (var content = avatar.content()) {
            response.setContentType(avatar.mediaType());
            response.setHeader("Cache-Control", "private, no-cache");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Disposition", "inline");
            response.setContentLengthLong(content.metadata().sizeBytes());
            content.inputStream().transferTo(response.getOutputStream());
        }
    }
    @GetMapping("/personas/{personaId}/models")
    @Operation(operationId = "listChatPersonaModels", summary = "List models available to this actor and agent")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<AvailableChatModelResponse> models(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID personaId) {
        return models.availableModelsForPersona(identity.actorId(), personaId).stream().map(AvailableChatModelResponse::from).toList();
    }
    @GetMapping("/personas/sources")
    @Operation(operationId = "listChatPersonaSources", summary = "List sources eligible for agent search")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<SourceSearchService.SourceOption> sources(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        return sources.options(identity.actorId(), offset, limit);
    }
    @GetMapping("/persona-share-options")
    @Operation(operationId = "listChatPersonaShareOptions", summary = "Search active members and ordinary Groups to share an agent with")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    ChatAgentShareOptionsResponse shareOptions(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(required = false) @Nullable String q, @RequestParam(defaultValue = "20") int limit) {
        return ChatAgentShareOptionsResponse.from(personas.shareOptions(identity.actorId(), q, limit));
    }
    @GetMapping("/persona-labels")
    @Operation(operationId = "listChatPersonaLabels", summary = "List agent labels")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<ChatAgentRefResponse> labels(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return personas.labels(identity.actorId()).stream().map(ChatAgentRefResponse::from).toList();
    }
    @PostMapping("/persona-labels")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createChatPersonaLabel", summary = "Create an agent label")
    @ApiResponse(responseCode = "201", description = "Successful chat operation", useReturnTypeSchema = true)
    ChatAgentRefResponse createLabel(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @RequestBody ChatPersonaLabelRequest request) {
        return ChatAgentRefResponse.from(personas.createLabel(identity.actorId(), request.name()));
    }
    @PutMapping("/persona-labels/{labelId}")
    @Operation(operationId = "renameChatPersonaLabel", summary = "Rename an agent label; requires AGENTS_MANAGE")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    ChatAgentRefResponse renameLabel(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID labelId, @RequestBody ChatPersonaLabelRequest request) {
        return ChatAgentRefResponse.from(personas.renameLabel(identity.actorId(), labelId, request.name()));
    }
    @DeleteMapping("/persona-labels/{labelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteChatPersonaLabel", summary = "Delete an agent label; requires AGENTS_MANAGE")
    void deleteLabel(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID labelId) {
        personas.deleteLabel(identity.actorId(), labelId);
    }
    @GetMapping("/persona-pins")
    @Operation(operationId = "listChatPersonaPins", summary = "List the actor's pinned agents in order")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<PersonaView> pins(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return personas.pins(identity.actorId());
    }
    @PutMapping("/persona-pins")
    @Operation(operationId = "replaceChatPersonaPins", summary = "Replace the actor's ordered pinned agents")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<PersonaView> replacePins(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @RequestBody ChatPersonaPinsRequest request) {
        return personas.replacePins(identity.actorId(), request.personaIds());
    }
}
