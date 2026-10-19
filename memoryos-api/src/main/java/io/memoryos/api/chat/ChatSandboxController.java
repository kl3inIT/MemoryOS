package io.memoryos.api.chat;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.chat.contract.SandboxHealthResponse;
import io.memoryos.api.chat.contract.SandboxSettingsRequest;
import io.memoryos.api.chat.contract.SandboxSettingsResponse;
import io.memoryos.chat.interpreter.InterpreterService;
import io.memoryos.sandbox.SandboxService;
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
@RequestMapping(value = "/api/chat/sandbox", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat Sandbox")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid Sandbox setting")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required")
@ApiResponse(responseCode = "404", description = "Tenant unavailable")
@ApiResponse(responseCode = "409", description = "Sandbox setting changed")
@ApiResponse(responseCode = "503", description = "Sandbox is not configured")
class ChatSandboxController {
    private final InterpreterService settings;
    private final SandboxService sandboxes;
    ChatSandboxController(InterpreterService settings, SandboxService sandboxes) {
        this.settings = settings; this.sandboxes = sandboxes;
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Sandbox setting", useReturnTypeSchema = true)
    @Operation(operationId = "getChatSandboxSettings", summary = "Read whether the Sandbox is configured and enabled for the Tenant, and its allowed domains")
    SandboxSettingsResponse get(@CurrentActor IdentityContext identity) {
        return SandboxSettingsResponse.from(settings.settings(identity.actorId()), sandboxes.allowedDomains());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "Saved Sandbox setting", useReturnTypeSchema = true)
    @Operation(operationId = "updateChatSandboxSettings", summary = "Enable or disable the Sandbox for the Tenant")
    SandboxSettingsResponse update(@CurrentActor IdentityContext identity,
                                       @Valid @RequestBody SandboxSettingsRequest request) {
        return SandboxSettingsResponse.from(settings.update(identity.actorId(), request.enabled(), request.revision()),
                sandboxes.allowedDomains());
    }

    @GetMapping("/health")
    @ApiResponse(responseCode = "200", description = "Live sandbox runtime health", useReturnTypeSchema = true)
    @Operation(operationId = "getChatSandboxHealth", summary = "Check the sandbox runtime")
    SandboxHealthResponse health(@CurrentActor IdentityContext identity) {
        settings.requireManager(identity.actorId());
        return SandboxHealthResponse.of(sandboxes.available(), sandboxes.healthy());
    }
}
