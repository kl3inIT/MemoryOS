package io.memoryos.api.chat;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.chat.contract.ChatEditTurnRequest;
import io.memoryos.api.chat.contract.ChatRegenerateTurnRequest;
import io.memoryos.api.chat.contract.ChatSendTurnRequest;
import io.memoryos.api.chat.contract.ChatTurnAcceptedResponse;
import io.memoryos.api.chat.contract.ChatTurnCancellationResponse;
import io.memoryos.chat.ChatTurnService;
import io.memoryos.chat.ChatCommand;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.MediaType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat/sessions/{sessionId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@ApiResponse(responseCode = "400", description = "Invalid request or cursor")
@ApiResponse(responseCode = "403", description = "Tenant membership or CSRF requirement not met")
@ApiResponse(responseCode = "404", description = "Conversation or message not accessible")
@ApiResponse(responseCode = "409", description = "Conflicting request or active reply")
@ApiResponse(responseCode = "503", description = "Chat capacity exhausted or provider unavailable")
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
    ChatTurnAcceptedResponse send(@CurrentActor IdentityContext identity,
                  @PathVariable UUID sessionId, @Valid @RequestBody ChatSendTurnRequest request) {
        var accepted = turns.command(identity.actorId(), sessionId, new ChatCommand(ChatCommand.Operation.SEND,
                request.parentMessageId(), request.clientRequestId(), request.text(), request.modelConfigurationId(), request.fileIds(), request.webSearch(), request.image(),
                Boolean.TRUE.equals(request.deepResearch()), servers(request.mcpServerIds())));
        return new ChatTurnAcceptedResponse(accepted.userMessageId(), accepted.assistantMessageId(), accepted.modelConfigurationId(), accepted.fallbackReason());
    }

    @PostMapping("/{assistantMessageId}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "cancelChatMessage", summary = "Request Stop; read history for the committed terminal outcome")
    @ApiResponse(responseCode = "202", description = "Stop requested; history contains the committed outcome", useReturnTypeSchema = true)
    ChatTurnCancellationResponse cancel(@CurrentActor IdentityContext identity,
                        @PathVariable UUID sessionId, @PathVariable UUID assistantMessageId) {
        var result = turns.cancel(identity.actorId(), sessionId, assistantMessageId);
        return new ChatTurnCancellationResponse(result.assistantMessageId(), result.status().name());
    }

    @PostMapping("/{userMessageId}/edit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "editChatMessage", summary = "Create a new question branch and execute its reply")
    @ApiResponse(responseCode = "202", description = "Reserved edited branch", useReturnTypeSchema = true)
    ChatTurnAcceptedResponse edit(@CurrentActor IdentityContext identity, @PathVariable UUID sessionId,
            @PathVariable UUID userMessageId, @Valid @RequestBody ChatEditTurnRequest request) {
        var accepted = turns.command(identity.actorId(), sessionId, new ChatCommand(ChatCommand.Operation.EDIT,
                userMessageId, request.clientRequestId(), request.text(), request.modelConfigurationId(), request.fileIds(), request.webSearch(), request.image(),
                Boolean.TRUE.equals(request.deepResearch()), servers(request.mcpServerIds())));
        return new ChatTurnAcceptedResponse(accepted.userMessageId(), accepted.assistantMessageId(), accepted.modelConfigurationId(), accepted.fallbackReason());
    }

    @PostMapping("/{userMessageId}/regenerate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "regenerateChatMessage", summary = "Generate a new answer under the existing question")
    @ApiResponse(responseCode = "202", description = "Reserved regenerated reply", useReturnTypeSchema = true)
    ChatTurnAcceptedResponse regenerate(@CurrentActor IdentityContext identity, @PathVariable UUID sessionId,
            @PathVariable UUID userMessageId, @Valid @RequestBody ChatRegenerateTurnRequest request) {
        var accepted = turns.command(identity.actorId(), sessionId, new ChatCommand(ChatCommand.Operation.REGENERATE,
                userMessageId, request.clientRequestId(), "", request.modelConfigurationId(), List.of(), request.webSearch(), request.image(),
                Boolean.TRUE.equals(request.deepResearch()), servers(request.mcpServerIds())));
        return new ChatTurnAcceptedResponse(accepted.userMessageId(), accepted.assistantMessageId(), accepted.modelConfigurationId(), accepted.fallbackReason());
    }

    private static List<UUID> servers(@Nullable List<UUID> selected) {
        return selected == null ? List.of() : selected;
    }

}
