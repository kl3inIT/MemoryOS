package io.memoryos.api.chat;

import io.memoryos.ai.AiException;
import io.memoryos.ai.ModelCatalogService;
import io.memoryos.ai.ModelFlow;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.ai.systemone.SystemOneClients;
import io.memoryos.ai.systemone.SystemOneConnectionService;
import io.memoryos.ai.systemone.SystemOneProvider;
import io.memoryos.api.chat.contract.ChatModelFlowResponse;
import io.memoryos.api.chat.contract.SystemOneConnectionRequest;
import io.memoryos.api.chat.contract.SystemOneConnectionResponse;
import io.memoryos.api.chat.contract.SystemOneTaskRequest;
import io.memoryos.api.chat.contract.SystemOneTypeResponse;
import io.memoryos.api.security.CurrentActor;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping(value = "/api/chat/system-one", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat System One")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid System One configuration")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required")
@ApiResponse(responseCode = "404", description = "System One connection unavailable")
@ApiResponse(responseCode = "409", description = "System One connection changed or in use")
@ApiResponse(responseCode = "503", description = "System One service unavailable")
class SystemOneController {
    private final SystemOneConnectionService connections;
    private final SystemOneClients clients;
    private final ModelCatalogService catalog;

    SystemOneController(SystemOneConnectionService connections, SystemOneClients clients, ModelCatalogService catalog) {
        this.connections = connections; this.clients = clients; this.catalog = catalog;
    }

    @GetMapping("/types")
    @ApiResponse(responseCode = "200", description = "System One connection types", useReturnTypeSchema = true)
    @Operation(operationId = "listSystemOneTypes", summary = "List the System One connection types and what each needs")
    List<SystemOneTypeResponse> types() {
        return connections.types().stream().map(SystemOneTypeResponse::from).toList();
    }

    @GetMapping("/connections")
    @ApiResponse(responseCode = "200", description = "System One connections", useReturnTypeSchema = true)
    @Operation(operationId = "listSystemOneConnections", summary = "List System One connections for model managers")
    List<SystemOneConnectionResponse> list(@CurrentActor IdentityContext identity) {
        return connections.list(identity.actorId()).stream().map(SystemOneConnectionResponse::from).toList();
    }

    @PostMapping("/types/{provider}/connections")
    @ApiResponse(responseCode = "200", description = "Created System One connection", useReturnTypeSchema = true)
    @Operation(operationId = "createSystemOneConnection", summary = "Add a System One connection of one type")
    SystemOneConnectionResponse create(@CurrentActor IdentityContext identity, @PathVariable SystemOneProvider provider,
            @Valid @RequestBody SystemOneConnectionRequest request) {
        return SystemOneConnectionResponse.from(connections.create(identity.actorId(), provider, input(request)));
    }

    @PutMapping("/connections/{id}")
    @ApiResponse(responseCode = "200", description = "Saved System One connection", useReturnTypeSchema = true)
    @Operation(operationId = "saveSystemOneConnection", summary = "Change a System One connection")
    SystemOneConnectionResponse save(@CurrentActor IdentityContext identity, @PathVariable UUID id,
            @Valid @RequestBody SystemOneConnectionRequest request) {
        return SystemOneConnectionResponse.from(connections.update(identity.actorId(), id, input(request)));
    }

    @DeleteMapping("/connections/{id}")
    @ApiResponse(responseCode = "204", description = "System One connection deleted", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteSystemOneConnection",
            summary = "Delete a System One connection; refused with 409 while a task runs on it")
    void delete(@CurrentActor IdentityContext identity, @PathVariable UUID id) {
        connections.delete(identity.actorId(), id);
    }

    @PostMapping("/connections/{id}/test")
    @ApiResponse(responseCode = "204", description = "The service answered a choice question", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "testSystemOneConnection",
            summary = "Ask the saved connection one self-contained question; provider charges may apply")
    void test(@CurrentActor IdentityContext identity, @PathVariable UUID id) {
        var connection = connections.forTest(identity.actorId(), id);
        try {
            clients.test(connection);
        } catch (RuntimeException failed) {
            throw AiException.providerUnavailable();
        }
    }

    @PutMapping("/tasks/{flow}")
    @ApiResponse(responseCode = "200", description = "The task and what it runs on", useReturnTypeSchema = true)
    @Operation(operationId = "setSystemOneTask",
            summary = "Run a classifying task on a System One connection instead of a language model; set a model with "
                    + "setChatModelFlow to return it to one")
    ChatModelFlowResponse assign(@CurrentActor IdentityContext identity, @PathVariable ModelFlow flow,
            @Valid @RequestBody SystemOneTaskRequest request) {
        connections.assign(identity.actorId(), flow, request.connectionId(), request.revision());
        return catalog.flowDefaults(identity.actorId()).stream().filter(view -> view.flow() == flow)
                .map(ChatModelFlowResponse::from).findFirst().orElseThrow(AiException::unavailable);
    }

    private static SystemOneConnectionService.Input input(SystemOneConnectionRequest request) {
        return new SystemOneConnectionService.Input(request.name(), request.endpoint(), request.model(),
                new ProviderCredentials.Change(request.credentialAction(), request.credentialValue()),
                request.dataBoundary(), request.inputPrice(), request.revision());
    }
}
