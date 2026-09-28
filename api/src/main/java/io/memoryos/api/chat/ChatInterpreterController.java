package io.memoryos.api.chat;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.chat.contract.InterpreterHealthResponse;
import io.memoryos.api.chat.contract.InterpreterSettingsRequest;
import io.memoryos.api.chat.contract.InterpreterSettingsResponse;
import io.memoryos.chat.interpreter.InterpreterClient;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat/interpreter", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat Interpreter")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid Code Interpreter setting")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required")
@ApiResponse(responseCode = "404", description = "Tenant unavailable")
@ApiResponse(responseCode = "409", description = "Code Interpreter setting changed")
@ApiResponse(responseCode = "503", description = "Code Interpreter is not configured")
class ChatInterpreterController {
    private final InterpreterService settings;
    private final InterpreterClient client;
    ChatInterpreterController(InterpreterService settings, InterpreterClient client) {
        this.settings = settings; this.client = client;
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Code Interpreter setting", useReturnTypeSchema = true)
    @Operation(operationId = "getChatInterpreterSettings", summary = "Read whether Code Interpreter is configured and enabled for the Tenant")
    InterpreterSettingsResponse get(@CurrentActor IdentityContext identity) {
        return InterpreterSettingsResponse.from(settings.settings(identity.actorId()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "Saved Code Interpreter setting", useReturnTypeSchema = true)
    @Operation(operationId = "updateChatInterpreterSettings", summary = "Enable or disable Code Interpreter for the Tenant")
    InterpreterSettingsResponse update(@CurrentActor IdentityContext identity,
                                       @Valid @RequestBody InterpreterSettingsRequest request) {
        return InterpreterSettingsResponse.from(settings.update(identity.actorId(), request.enabled(), request.revision()));
    }

    @GetMapping("/health")
    @ApiResponse(responseCode = "200", description = "Live Code Interpreter service health", useReturnTypeSchema = true)
    @Operation(operationId = "getChatInterpreterHealth", summary = "Check the Code Interpreter service without the cache")
    InterpreterHealthResponse health(@CurrentActor IdentityContext identity) {
        settings.requireManager(identity.actorId());
        return InterpreterHealthResponse.from(client.health());
    }
}
