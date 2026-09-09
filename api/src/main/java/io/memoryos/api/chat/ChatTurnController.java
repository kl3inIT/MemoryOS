package io.memoryos.api.chat;

import io.memoryos.chat.ChatTurnService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.MediaType;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat/sessions/{sessionId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid request or cursor",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "403", description = "Tenant membership or CSRF requirement not met",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Conversation or message not accessible",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "409", description = "Conflicting request or active reply",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "503", description = "Chat capacity exhausted or provider unavailable",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class ChatTurnController {
    private final ChatTurnService turns;

    ChatTurnController(ChatTurnService turns) {
        this.turns = turns;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "sendChatMessage", summary = "Reserve and execute a chat reply in the background")
    @ApiResponse(responseCode = "202", description = "Reserved reply", useReturnTypeSchema = true)
    Accepted send(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                  @PathVariable UUID sessionId, @Valid @RequestBody Send request) {
        var accepted = turns.send(identity.actorId(), sessionId, request.parentMessageId(), request.clientRequestId(), request.text());
        return new Accepted(accepted.userMessageId(), accepted.assistantMessageId());
    }

    @PostMapping("/{assistantMessageId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "cancelChatMessage", summary = "Request Stop; read history for the committed terminal outcome")
    @ApiResponse(responseCode = "202", description = "Stop requested; history contains the committed outcome", useReturnTypeSchema = true)
    Cancellation cancel(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
                        @PathVariable UUID sessionId, @PathVariable UUID assistantMessageId) {
        var result = turns.cancel(identity.actorId(), sessionId, assistantMessageId);
        return new Cancellation(result.assistantMessageId(), result.status().name());
    }

    record Send(@NotNull UUID parentMessageId, @NotNull UUID clientRequestId,
                @NotBlank @Size(max = 32000) String text) {
    }

    record Accepted(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userMessageId,
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID assistantMessageId) {
    }

    record Cancellation(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID assistantMessageId,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"RUNNING", "COMPLETED", "CANCELED", "FAILED"}) String status) {
    }
}
