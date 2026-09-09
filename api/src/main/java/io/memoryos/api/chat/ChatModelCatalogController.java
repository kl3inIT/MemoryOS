package io.memoryos.api.chat;

import io.memoryos.chat.catalog.ChatProviderAdapters;
import io.memoryos.chat.catalog.ModelCatalogService;
import io.memoryos.chat.catalog.ModelCatalogService.ModelInput;
import io.memoryos.chat.catalog.ModelCatalogService.ProviderInput;
import io.memoryos.chat.catalog.ModelCatalogService.ProviderView;
import io.memoryos.chat.persistence.JdbcModelCatalogRepository;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat models")
@ApiResponse(responseCode = "400", description = "Invalid configuration", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Model management, Tenant membership or CSRF requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Resource not accessible", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "409", description = "Stale revision or duplicate model", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "503", description = "Provider, encryption key or client capacity unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatModelCatalogController {
    private final ModelCatalogService catalog;
    private final ChatProviderAdapters adapters;
    private final ChatModelValidation validation;
    ChatModelCatalogController(ModelCatalogService catalog, ChatProviderAdapters adapters, ChatModelValidation validation) {
        this.catalog = catalog;
        this.adapters = adapters;
        this.validation = validation;
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/models")
    @Operation(operationId = "listAvailableChatModels", summary = "List visible models authorized for this session or the builtin Persona")
    List<ModelCatalogService.AvailableModel> available(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                                       @RequestParam(required = false) @Nullable UUID sessionId) {
        return catalog.availableModels(identity.actorId(), sessionId);
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/provider-adapters")
    @Operation(operationId = "listChatProviderAdapters", summary = "List installed adapter types and credential requirements; requires model management")
    List<ChatProviderAdapters.Descriptor> adapters(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        catalog.requireModelsManage(identity.actorId());
        return adapters.available();
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/providers")
    @Operation(operationId = "listChatProviders", summary = "List Tenant provider configuration with credentials redacted")
    List<ProviderView> providers(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return catalog.providers(identity.actorId());
    }
    @PostMapping("/providers")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createChatProvider", summary = "Create a Tenant provider; requires MODELS_MANAGE")
    ProviderView createProvider(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @RequestBody ProviderInput request) {
        return catalog.createProvider(identity.actorId(), request);
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PutMapping("/providers/{providerId}")
    @Operation(operationId = "updateChatProvider", summary = "Replace provider settings at the expected revision; credential action is explicit")
    ProviderView updateProvider(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID providerId, @RequestParam @Positive long revision, @RequestBody ProviderInput request) {
        return catalog.updateProvider(identity.actorId(), providerId, revision, request);
    }
    @DeleteMapping("/providers/{providerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteChatProvider", summary = "Delete a provider that is not the Chat default; transcript is preserved")
    void deleteProvider(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                        @PathVariable UUID providerId, @RequestParam @Positive long revision) {
        catalog.deleteProvider(identity.actorId(), providerId, revision);
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/providers/{providerId}/models")
    @Operation(operationId = "listConfiguredChatModels", summary = "List all configured models on a provider; requires model management")
    List<JdbcModelCatalogRepository.Model> configured(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID providerId) {
        return catalog.models(identity.actorId(), providerId);
    }
    @PostMapping("/providers/{providerId}/models")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createChatModel", summary = "Add a concrete model configuration to a Tenant provider")
    JdbcModelCatalogRepository.Model createModel(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                                @PathVariable UUID providerId, @RequestBody ModelInput request) {
        return catalog.createModel(identity.actorId(), providerId, request);
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PutMapping("/models/{modelId}")
    @Operation(operationId = "updateChatModel", summary = "Replace a model configuration at the expected revision")
    JdbcModelCatalogRepository.Model updateModel(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID modelId, @RequestParam @Positive long revision, @RequestBody ModelInput request) {
        return catalog.updateModel(identity.actorId(), modelId, revision, request);
    }
    @DeleteMapping("/models/{modelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteChatModel", summary = "Delete a non-default model and clear its Persona defaults; transcript is preserved")
    void deleteModel(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                     @PathVariable UUID modelId, @RequestParam @Positive long revision) {
        catalog.deleteModel(identity.actorId(), modelId, revision);
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/model-default")
    @Operation(operationId = "getChatModelDefault", summary = "Read the Tenant Chat default and its revision; requires model management")
    JdbcModelCatalogRepository.Default defaultModel(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return catalog.defaultModel(identity.actorId());
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PutMapping("/model-default")
    @Operation(operationId = "setChatModelDefault", summary = "Set a visible, publicly available Tenant Chat default")
    JdbcModelCatalogRepository.Default setDefault(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam UUID modelConfigurationId, @RequestParam @Positive long revision) {
        return catalog.setDefault(identity.actorId(), modelConfigurationId, revision);
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/personas/{personaId}/model")
    @Operation(operationId = "getPersonaModel", summary = "Read the Persona model selection and revision; requires model management")
    JdbcModelCatalogRepository.PersonaModel persona(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId) {
        return catalog.personaModel(identity.actorId(), personaId);
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PutMapping("/personas/{personaId}/model")
    @Operation(operationId = "setPersonaModel", summary = "Set a Persona model or omit the model ID to inherit the Chat default")
    JdbcModelCatalogRepository.PersonaModel setPersona(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID personaId, @RequestParam(required = false) @Nullable UUID modelConfigurationId, @RequestParam @Positive long revision) {
        return catalog.setPersonaModel(identity.actorId(), personaId, modelConfigurationId, revision);
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PostMapping("/models/{modelId}/validate")
    @Operation(operationId = "validateChatModel", summary = "Explicit bounded provider connectivity check; does not certify model capabilities")
    ChatModelValidation.Result validate(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID modelId) {
        return validation.validate(identity.actorId(), modelId);
    }
}
