package io.memoryos.api.search;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.search.contract.EmbeddingModelPresetResponse;
import io.memoryos.api.search.contract.EmbeddingProviderRequest;
import io.memoryos.api.search.contract.EmbeddingProviderResponse;
import io.memoryos.api.search.contract.EmbeddingProviderTestRequest;
import io.memoryos.api.search.contract.EmbeddingProviderTestResponse;
import io.memoryos.api.search.contract.SearchGenerationRequest;
import io.memoryos.api.search.contract.SearchGenerationResponse;
import io.memoryos.api.search.contract.SearchSettingsResponse;
import io.memoryos.iam.IdentityContext;
import io.memoryos.retrieval.settings.SearchSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Search settings (MEM-135): the embedding model, its providers and the lifecycle of the indexes. The index is shared
 * by every Tenant, so every operation requires model management of the operating Tenant.
 */
@RestController
@RequestMapping(value = "/api/search", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Search settings")
@ApiResponse(responseCode = "400", description = "Invalid configuration")
@ApiResponse(responseCode = "403", description = "Model management of the operating Tenant or CSRF requirement not met")
@ApiResponse(responseCode = "404", description = "Resource not accessible")
@ApiResponse(responseCode = "409", description = "Conflicting search settings state")
@ApiResponse(responseCode = "503", description = "Embedding provider, encryption key or index unavailable")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class SearchSettingsController {
    private final SearchSettingsService settings;

    SearchSettingsController(SearchSettingsService settings) { this.settings = settings; }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/settings")
    @Operation(operationId = "getSearchSettings",
            summary = "Read the present, future and retained past search generations with rebuild progress")
    SearchSettingsResponse settings(@CurrentActor IdentityContext identity) {
        return SearchSettingsResponse.from(settings.settings(identity.actorId()));
    }

    @ApiResponse(responseCode = "409", description = "A future generation already exists")
    @PostMapping("/settings/future")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createSearchFutureGeneration",
            summary = "Start rebuilding the index for a new embedding model as the future generation")
    SearchGenerationResponse createFuture(@CurrentActor IdentityContext identity,
            @RequestBody SearchGenerationRequest request) {
        return SearchGenerationResponse.from(settings.createFuture(identity.actorId(), request.toInput()));
    }

    @DeleteMapping("/settings/future")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "cancelSearchFutureGeneration", summary = "Cancel the rebuild and delete the future generation's index")
    void cancelFuture(@CurrentActor IdentityContext identity) {
        settings.cancelFuture(identity.actorId());
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PostMapping("/settings/future/switch")
    @Operation(operationId = "switchSearchFutureGeneration",
            summary = "Make the fully rebuilt future generation present; the present one becomes past")
    SearchSettingsResponse switchFuture(@CurrentActor IdentityContext identity) {
        return SearchSettingsResponse.from(settings.switchFuture(identity.actorId()));
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PostMapping("/settings/past/{generationId}/restore")
    @Operation(operationId = "restoreSearchPastGeneration", summary = "Make a retained past generation present again")
    SearchSettingsResponse restorePast(@CurrentActor IdentityContext identity,
            @PathVariable UUID generationId) {
        return SearchSettingsResponse.from(settings.restorePast(identity.actorId(), generationId));
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/embedding-providers")
    @Operation(operationId = "listEmbeddingProviders", summary = "List embedding providers with keys redacted")
    List<EmbeddingProviderResponse> providers(@CurrentActor IdentityContext identity) {
        return settings.providers(identity.actorId()).stream().map(EmbeddingProviderResponse::from).toList();
    }

    @PostMapping("/embedding-providers")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createEmbeddingProvider", summary = "Create an OpenAI-compatible embedding provider")
    EmbeddingProviderResponse createProvider(@CurrentActor IdentityContext identity,
            @RequestBody EmbeddingProviderRequest request) {
        return EmbeddingProviderResponse.from(settings.createProvider(identity.actorId(), request.toInput()));
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @PutMapping("/embedding-providers/{providerId}")
    @Operation(operationId = "updateEmbeddingProvider", summary = "Replace an embedding provider at the expected revision")
    EmbeddingProviderResponse updateProvider(@CurrentActor IdentityContext identity,
            @PathVariable UUID providerId, @RequestBody EmbeddingProviderRequest request) {
        return EmbeddingProviderResponse.from(settings.updateProvider(identity.actorId(), providerId, request.toInput()));
    }

    @ApiResponse(responseCode = "409", description = "A search generation still uses the provider")
    @DeleteMapping("/embedding-providers/{providerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteEmbeddingProvider", summary = "Delete an embedding provider that no search generation uses")
    void deleteProvider(@CurrentActor IdentityContext identity, @PathVariable UUID providerId) {
        settings.deleteProvider(identity.actorId(), providerId);
    }

    @ApiResponse(responseCode = "200", description = "The outcome of one embedding call", useReturnTypeSchema = true)
    @PostMapping("/embedding-providers/test")
    @Operation(operationId = "testEmbeddingProvider",
            summary = "Check a saved or unsaved embedding endpoint with one real /v1/embeddings call")
    EmbeddingProviderTestResponse testProvider(@CurrentActor IdentityContext identity,
            @RequestBody EmbeddingProviderTestRequest request) {
        return EmbeddingProviderTestResponse.from(settings.testProvider(identity.actorId(), request.toInput()));
    }

    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @GetMapping("/embedding-models")
    @Operation(operationId = "listEmbeddingModelPresets",
            summary = "List known embedding models whose dimensions and prefixes are prefilled")
    List<EmbeddingModelPresetResponse> presets(@CurrentActor IdentityContext identity) {
        return settings.presets(identity.actorId()).stream().map(EmbeddingModelPresetResponse::from).toList();
    }
}
