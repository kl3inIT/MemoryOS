package io.memoryos.api.chat;

import io.memoryos.chat.catalog.ChatProviderAdapters;
import io.memoryos.chat.catalog.ChatModelResolver;
import io.memoryos.chat.catalog.ModelCatalogService;
import io.memoryos.chat.catalog.ModelFlow;
import io.memoryos.api.chat.contract.AvailableChatModelResponse;
import io.memoryos.api.chat.contract.ChatGroupPageResponse;
import io.memoryos.api.chat.contract.ChatModelDefaultResponse;
import io.memoryos.api.chat.contract.ChatModelFlowResponse;
import io.memoryos.api.chat.contract.ChatModelRequest;
import io.memoryos.api.chat.contract.ChatModelResponse;
import io.memoryos.api.chat.contract.ChatModelValidationResponse;
import io.memoryos.api.chat.contract.ChatPersonaModelResponse;
import io.memoryos.api.chat.contract.ChatPersonaPageResponse;
import io.memoryos.api.chat.contract.ChatProviderAdapterResponse;
import io.memoryos.api.chat.contract.ChatProviderRequest;
import io.memoryos.api.chat.contract.ChatProviderResponse;
import io.memoryos.api.chat.contract.ChatProviderTestRequest;
import io.memoryos.api.chat.contract.ChatProviderTestResponse;
import io.memoryos.api.chat.contract.ChatReportedModelsResponse;
import io.memoryos.chat.catalog.openai.ChatModelValidation;
import io.memoryos.iam.group.GroupQuery;
import io.memoryos.iam.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
    private final ChatModelResolver models;
    ChatModelCatalogController(ModelCatalogService catalog, ChatProviderAdapters adapters,
                               ChatModelValidation validation, ChatModelResolver models) {
        this.catalog = catalog;
        this.adapters = adapters;
        this.validation = validation;
        this.models = models;
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/models")
    @Operation(operationId = "listAvailableChatModels", summary = "List visible models authorized for this session or the builtin Persona")
    List<AvailableChatModelResponse> available(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                                       @RequestParam(required = false) @Nullable UUID sessionId) {
        return catalog.availableModels(identity.actorId(), sessionId).stream().map(AvailableChatModelResponse::from).toList();
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/provider-adapters")
    @Operation(operationId = "listChatProviderAdapters", summary = "List installed adapter types and credential requirements; requires model management")
    List<ChatProviderAdapterResponse> adapters(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        catalog.requireModelsManage(identity.actorId());
        return adapters.available().stream().map(ChatProviderAdapterResponse::from).toList();
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/group-options")
    @Operation(operationId = "listChatGroupOptions", summary = "List Groups available for provider access; requires model management")
    ChatGroupPageResponse groupOptions(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(required = false) @Nullable @Size(max = GroupQuery.MAX_SEARCH_LENGTH) String search,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(GroupQuery.MAX_SIZE) int size) {
        return ChatGroupPageResponse.from(catalog.groupOptions(identity.actorId(), search, page, size));
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/providers")
    @Operation(operationId = "listChatProviders", summary = "List Tenant provider configuration with credentials redacted")
    List<ChatProviderResponse> providers(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return catalog.providers(identity.actorId()).stream().map(ChatProviderResponse::from).toList();
    }
    @PostMapping("/providers")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createChatProvider", summary = "Create a Tenant provider; requires MODELS_MANAGE")
    ChatProviderResponse createProvider(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @RequestBody ChatProviderRequest request) {
        verifyBeforeSave(identity, null, request);
        return ChatProviderResponse.from(catalog.createProvider(identity.actorId(), request.toInput()));
    }

    @ApiResponse(responseCode = "200", description = "The provider accepted the endpoint and key", useReturnTypeSchema = true)
    @PostMapping("/providers/test")
    @Operation(operationId = "testChatProvider",
            summary = "Check an unsaved or edited provider endpoint and key by listing its models; requires model management")
    ChatProviderTestResponse testProvider(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                          @RequestBody ChatProviderTestRequest request) {
        var probe = catalog.probeProvider(identity.actorId(), request.providerId(), request.adapterType(), request.baseUrl(),
                request.credential() == null ? null : request.credential().toInput());
        long started = System.nanoTime();
        int count = models.verifyProvider(probe.connection());
        return ChatProviderTestResponse.of(count, java.time.Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    /**
     * Onyx parity: an enabled provider whose endpoint or key rejects the check is not saved. The check runs outside
     * any transaction; an update that keeps its endpoint and key is not re-checked, so renaming or regrouping a
     * provider never depends on the endpoint being up.
     */
    private void verifyBeforeSave(IdentityContext identity, @Nullable UUID providerId, ChatProviderRequest request) {
        if (!request.enabled()) return;
        var probe = catalog.probeProvider(identity.actorId(), providerId, request.adapterType(), request.baseUrl(),
                request.credential() == null ? null : request.credential().toInput());
        if (probe.changed()) models.verifyProvider(probe.connection());
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PutMapping("/providers/{providerId}")
    @Operation(operationId = "updateChatProvider", summary = "Replace provider settings at the expected revision; credential action is explicit")
    ChatProviderResponse updateProvider(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID providerId, @RequestParam @Positive long revision, @RequestBody ChatProviderRequest request) {
        verifyBeforeSave(identity, providerId, request);
        return ChatProviderResponse.from(catalog.updateProvider(identity.actorId(), providerId, revision, request.toInput()));
    }
    @DeleteMapping("/providers/{providerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteChatProvider", summary = "Delete a provider that is not the Chat default; transcript is preserved")
    void deleteProvider(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                        @PathVariable UUID providerId, @RequestParam @Positive long revision) {
        catalog.deleteProvider(identity.actorId(), providerId, revision);
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PostMapping("/providers/reported-models")
    @Operation(operationId = "listReportedProviderModels",
            summary = "List the models an endpoint reports with the limits, capabilities and prices it or the installed catalog publishes; "
                    + "takes the provider being edited, saved or not, as the connection check does; requires model management")
    ChatReportedModelsResponse reportedModels(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestBody ChatProviderTestRequest request) {
        var probe = catalog.probeProvider(identity.actorId(), request.providerId(), request.adapterType(), request.baseUrl(),
                request.credential() == null ? null : request.credential().toInput());
        return ChatReportedModelsResponse.from(models.reportedModels(probe.connection()));
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/providers/{providerId}/models")
    @Operation(operationId = "listConfiguredChatModels", summary = "List all configured models on a provider; requires model management")
    List<ChatModelResponse> configured(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID providerId) {
        return catalog.models(identity.actorId(), providerId).stream().map(ChatModelResponse::from).toList();
    }
    @PostMapping("/providers/{providerId}/models")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createChatModel", summary = "Add a concrete model configuration to a Tenant provider")
    ChatModelResponse createModel(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                                                @PathVariable UUID providerId, @RequestBody ChatModelRequest request) {
        return ChatModelResponse.from(catalog.createModel(identity.actorId(), providerId, request.toInput()));
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PutMapping("/models/{modelId}")
    @Operation(operationId = "updateChatModel", summary = "Replace a model configuration at the expected revision")
    ChatModelResponse updateModel(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID modelId, @RequestParam @Positive long revision, @RequestBody ChatModelRequest request) {
        return ChatModelResponse.from(catalog.updateModel(identity.actorId(), modelId, revision, request.toInput()));
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
    ChatModelDefaultResponse defaultModel(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return ChatModelDefaultResponse.from(catalog.defaultModel(identity.actorId()));
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PutMapping("/model-default")
    @Operation(operationId = "setChatModelDefault", summary = "Set a visible, publicly available Tenant Chat default")
    ChatModelDefaultResponse setDefault(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam UUID modelConfigurationId, @RequestParam @Positive long revision) {
        return ChatModelDefaultResponse.from(catalog.setDefault(identity.actorId(), modelConfigurationId, revision));
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/model-flows")
    @Operation(operationId = "listChatModelFlows", summary = "Read the Tenant model of every task flow and its revision; requires model management")
    List<ChatModelFlowResponse> flows(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return catalog.flowDefaults(identity.actorId()).stream().map(ChatModelFlowResponse::from).toList();
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PutMapping("/model-flows/{flow}")
    @Operation(operationId = "setChatModelFlow",
            summary = "Set a visible, publicly available task model, or omit the model ID to use the conversation model")
    ChatModelFlowResponse setFlow(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable ModelFlow flow,
            @RequestParam(required = false) @Nullable UUID modelConfigurationId, @RequestParam @Positive long revision) {
        return ChatModelFlowResponse.from(catalog.setFlowDefault(identity.actorId(), flow, modelConfigurationId, revision));
    }
    @ApiResponse(responseCode = "200", description = "Tenant Persona page", useReturnTypeSchema = true)
    @GetMapping("/model-personas")
    @Operation(operationId = "listChatModelPersonas", summary = "List builtin and actor-owned Personas for model selection; requires MODELS_MANAGE")
    ChatPersonaPageResponse personas(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Parameter(description = "Canonical last-returned UUID; must identify an accessible, nondeleted Persona",
                    schema = @Schema(type = "string", format = "uuid", minLength = 36, maxLength = 36))
            @RequestParam(required = false) @Nullable String cursor,
            @Parameter(schema = @Schema(type = "integer", format = "int32", minimum = "1", maximum = "100", defaultValue = "25"))
            @RequestParam(defaultValue = "25") int limit) {
        return ChatPersonaPageResponse.from(catalog.personas(identity.actorId(), cursor, limit));
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/personas/{personaId}/model")
    @Operation(operationId = "getPersonaModel", summary = "Read the Persona model selection and revision; requires model management")
    ChatPersonaModelResponse persona(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID personaId) {
        return ChatPersonaModelResponse.from(catalog.personaModel(identity.actorId(), personaId));
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PutMapping("/personas/{personaId}/model")
    @Operation(operationId = "setPersonaModel", summary = "Set a Persona model or omit the model ID to inherit the Chat default")
    ChatPersonaModelResponse setPersona(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID personaId, @RequestParam(required = false) @Nullable UUID modelConfigurationId, @RequestParam @Positive long revision) {
        return ChatPersonaModelResponse.from(catalog.setPersonaModel(identity.actorId(), personaId, modelConfigurationId, revision));
    }
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PostMapping("/models/{modelId}/validate")
    @Operation(operationId = "validateChatModel", summary = "Explicit bounded provider connectivity check; does not certify model capabilities")
    ChatModelValidationResponse validate(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID modelId) {
        var result = validation.validate(identity.actorId(), modelId);
        return new ChatModelValidationResponse(result.reachable(), result.failureCode());
    }
}
