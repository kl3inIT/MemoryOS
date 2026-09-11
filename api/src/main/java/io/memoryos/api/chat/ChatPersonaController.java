package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.AvailableChatModelResponse;
import io.memoryos.chat.ChatPersonaService;
import io.memoryos.chat.ChatPersonaService.PersonaInput;
import io.memoryos.chat.ChatPersonaService.PersonaView;
import io.memoryos.chat.catalog.ModelCatalogService;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/chat/personas", produces = MediaType.APPLICATION_JSON_VALUE)
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
    private final ModelCatalogService models;
    private final SourceSearchService sources;
    ChatPersonaController(ChatPersonaService personas, ModelCatalogService models, SourceSearchService sources) {
        this.personas = personas; this.models = models; this.sources = sources;
    }
    @GetMapping
    @Operation(operationId = "listChatPersonas", summary = "List the default and actor-owned assistants")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<PersonaView> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
        return personas.list(identity.actorId(), offset, limit);
    }
    @GetMapping("/{personaId}")
    @Operation(operationId = "getChatPersona", summary = "Read an authorized assistant")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    PersonaView get(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId) {
        return personas.get(identity.actorId(), personaId);
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createChatPersona", summary = "Create a private assistant")
    @ApiResponse(responseCode = "201", description = "Successful chat operation", useReturnTypeSchema = true)
    PersonaView create(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @RequestBody PersonaInput request) {
        return personas.create(identity.actorId(), request);
    }
    @PutMapping("/{personaId}")
    @Operation(operationId = "updateChatPersona", summary = "Update assistant settings with an expected revision")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    PersonaView update(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId,
            @RequestParam long revision, @RequestBody PersonaInput request) {
        return personas.update(identity.actorId(), personaId, revision, request);
    }
    @DeleteMapping("/{personaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteChatPersona", summary = "Delete a private assistant while retaining conversation history")
    void delete(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId, @RequestParam long revision) {
        personas.delete(identity.actorId(), personaId, revision);
    }
    @GetMapping("/{personaId}/models")
    @Operation(operationId = "listChatPersonaModels", summary = "List models available to this actor and assistant")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<AvailableChatModelResponse> models(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID personaId) {
        return models.availableModelsForPersona(identity.actorId(), personaId).stream().map(AvailableChatModelResponse::from).toList();
    }
    @GetMapping("/sources")
    @Operation(operationId = "listChatPersonaSources", summary = "List sources eligible for assistant search")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<SourceSearchService.SourceOption> sources(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        return sources.options(identity.actorId(), offset, limit);
    }
}
