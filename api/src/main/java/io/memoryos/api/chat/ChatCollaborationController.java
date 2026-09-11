package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ChatMessageResponse;
import io.memoryos.chat.ChatCollaborationService;
import io.memoryos.chat.ChatCollaborationService.Feedback;
import io.memoryos.chat.ChatCollaborationService.Sharing;
import io.memoryos.chat.ChatCollaborationService.SharedSession;
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
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
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
class ChatCollaborationController {
    private final ChatCollaborationService collaboration;
    ChatCollaborationController(ChatCollaborationService collaboration) { this.collaboration = collaboration; }
    @GetMapping("/sessions/{sessionId}/sharing")
    @Operation(operationId = "getChatSharing", summary = "Read the owner's sharing settings")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    Sharing sharing(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sessionId) {
        return collaboration.sharing(identity.actorId(), sessionId);
    }
    @PutMapping("/sessions/{sessionId}/sharing")
    @Operation(operationId = "setChatSharing", summary = "Enable or revoke read access for Tenant members with the link")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    Sharing share(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sessionId, @RequestBody Sharing request) {
        return collaboration.share(identity.actorId(), sessionId, request.enabled(), request.revision());
    }
    @GetMapping("/shared/{sessionId}")
    @Operation(operationId = "getSharedChatSession", summary = "Read a shared conversation after authenticating in its Tenant")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    SharedSession shared(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sessionId) {
        return collaboration.shared(identity.actorId(), sessionId);
    }
    @GetMapping("/shared/{sessionId}/messages")
    @Operation(operationId = "getSharedChatHistory", summary = "Read saved messages on the currently shared branch")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<ChatMessageResponse> history(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sessionId,
            @RequestParam(required = false) @Nullable UUID after, @RequestParam(defaultValue = "50") int limit) {
        return collaboration.sharedHistory(identity.actorId(), sessionId, after, limit).stream().map(ChatMessageResponse::from).toList();
    }
    @GetMapping("/sessions/{sessionId}/feedback")
    @Operation(operationId = "getChatFeedback", summary = "Read the actor's ratings for saved outputs")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    List<Feedback> feedback(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sessionId,
            @RequestParam List<UUID> messageIds) { return collaboration.feedback(identity.actorId(), sessionId, messageIds); }
    @PutMapping("/sessions/{sessionId}/messages/{assistantMessageId}/feedback")
    @Operation(operationId = "setChatFeedback", summary = "Add or change a rating on an owned assistant output")
    @ApiResponse(responseCode = "200", description = "Successful chat operation", useReturnTypeSchema = true)
    Feedback feedback(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sessionId,
            @PathVariable UUID assistantMessageId, @RequestBody FeedbackInput request) {
        return collaboration.feedback(identity.actorId(), sessionId, assistantMessageId, request.positive(), request.comment(), request.reason());
    }
    @DeleteMapping("/sessions/{sessionId}/messages/{assistantMessageId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "removeChatFeedback", summary = "Remove the actor's rating from an owned assistant output")
    void remove(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable UUID sessionId,
            @PathVariable UUID assistantMessageId) { collaboration.removeFeedback(identity.actorId(), sessionId, assistantMessageId); }
    record FeedbackInput(@Nullable Boolean positive, String comment, String reason) {}
}
