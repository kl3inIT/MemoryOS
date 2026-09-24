package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.WebAvailabilityResponse;
import io.memoryos.api.chat.contract.WebConnectionRequest;
import io.memoryos.api.chat.contract.WebConnectionResponse;
import io.memoryos.api.chat.contract.WebEnginesRequest;
import io.memoryos.api.chat.contract.WebEnginesResponse;
import io.memoryos.api.chat.contract.WebSelectionRequest;
import io.memoryos.api.chat.contract.WebTestRequest;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatModelAccess;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.chat.web.WebConnectionService;
import io.memoryos.chat.web.WebProvider;
import io.memoryos.chat.web.WebProviderClient;
import io.memoryos.iam.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@RequestMapping(value = "/api/chat/web", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat Web")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid Web configuration", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Web connection unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "409", description = "Web connection changed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "503", description = "Web provider unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
class WebConnectionController {
    private final WebConnectionService connections;
    private final WebProviderClient client;
    private final ChatModelAccess models;
    WebConnectionController(WebConnectionService connections, WebProviderClient client, ChatModelAccess models) {
        this.connections = connections; this.client = client; this.models = models;
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Available Web capabilities", useReturnTypeSchema = true)
    @Operation(operationId = "getChatWebAvailability", summary = "Read configured Web availability without credentials")
    WebAvailabilityResponse availability(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(required = false) UUID sessionId) {
        var access = connections.resolve(identity.actorId());
        var supported = models.availableWebModels(identity.actorId(), sessionId);
        return new WebAvailabilityResponse(access.search() != null, true,
                access.search() == null ? null : access.search().provider(), access.content() == null ? null : access.content().provider(),
                supported.automatic(), supported.inherited(), supported.nativeSearch());
    }
    @GetMapping("/connections")
    @ApiResponse(responseCode = "200", description = "Web connections", useReturnTypeSchema = true)
    @Operation(operationId = "listChatWebConnections", summary = "List Web connections for model managers")
    List<WebConnectionResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return connections.list(identity.actorId()).stream().map(WebConnectionResponse::from).toList();
    }
    @PutMapping("/connections/{provider}")
    @ApiResponse(responseCode = "200", description = "Saved Web connection", useReturnTypeSchema = true)
    @Operation(operationId = "saveChatWebConnection", summary = "Configure one Web connection without automatically enabling it")
    WebConnectionResponse save(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable WebProvider provider, @Valid @RequestBody WebConnectionRequest request) {
        return WebConnectionResponse.from(connections.save(identity.actorId(), provider, new WebConnectionService.Input(request.endpoint(), request.engineId(),
                new ProviderCredentials.Change(request.credentialAction(), request.credentialValue()), request.revision())));
    }
    @PutMapping("/selection")
    @ApiResponse(responseCode = "204", description = "Web selection saved", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "selectChatWebProvider", summary = "Select the search or content provider; null disables search or restores built-in reading")
    void select(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @Valid @RequestBody WebSelectionRequest request) {
        connections.select(identity.actorId(), request.search(), request.provider());
    }
    @PostMapping("/connections/{provider}/engines")
    @ApiResponse(responseCode = "200", description = "Successful result", useReturnTypeSchema = true)
    @Operation(operationId = "listChatWebEngines",
            summary = "List the search engines a 9Router gateway offers, with the typed key or the saved one for the same endpoint")
    WebEnginesResponse engines(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable WebProvider provider, @Valid @RequestBody WebEnginesRequest request) {
        if (provider != WebProvider.NINEROUTER) throw ChatException.invalid("This provider has no engine list.");
        String key = connections.discoveryKey(identity.actorId(), provider, request.endpoint(), request.key());
        try {
            return new WebEnginesResponse(client.nineRouterEngines(request.endpoint(), key));
        } catch (IOException | RuntimeException failed) {
            throw ChatException.providerUnavailable();
        }
    }
    @PostMapping("/connections/{provider}/test")
    @ApiResponse(responseCode = "204", description = "Provider request succeeded", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "testChatWebConnection", summary = "Explicitly perform a provider request; provider charges may apply")
    void test(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable WebProvider provider, @Valid @RequestBody WebTestRequest request) {
        var connection = connections.forTest(identity.actorId(), provider);
        if (!(request.search() ? provider.search() : provider.content())) throw ChatException.invalid("Unsupported Web connection test.");
        try {
            if (request.search()) {
                if (client.search(connection, "example.com").isEmpty()) throw new IOException("No usable test results");
            }
            else if (client.read(connection, "https://example.com", () -> {}).text().isBlank()) throw new IOException("Empty content");
        } catch (IOException | IllegalArgumentException failed) { throw ChatException.providerUnavailable(); }
    }
}
