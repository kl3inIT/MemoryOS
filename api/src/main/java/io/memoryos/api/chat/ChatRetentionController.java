package io.memoryos.api.chat;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.chat.contract.ChatRetentionPolicyRequest;
import io.memoryos.api.chat.contract.ChatRetentionPolicyResponse;
import io.memoryos.api.chat.contract.ChatRetentionPreviewResponse;
import io.memoryos.chat.ChatRetentionService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * How long the caller keeps their own conversations. Every route here reads and writes one person's policy —
 * their own — so a member needs no further authority, and nobody reaches anyone else's.
 */
@RestController
@RequestMapping(value = "/api/chat/retention", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "The number of days is outside 1 to 3650")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "CSRF requirement not met")
@ApiResponse(responseCode = "404", description = "Chat is unavailable or the person is not a member")
class ChatRetentionController {
    private final ChatRetentionService retention;

    ChatRetentionController(ChatRetentionService retention) { this.retention = retention; }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Your retention policy", useReturnTypeSchema = true)
    @Operation(operationId = "getChatRetention", summary = "Read how long you keep your own conversations")
    ResponseEntity<ChatRetentionPolicyResponse> read(@CurrentActor IdentityContext identity) {
        return ResponseEntity.ok()
                .body(new ChatRetentionPolicyResponse(retention.read(identity.actorId()).days()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "The policy after the change", useReturnTypeSchema = true)
    @Operation(operationId = "saveChatRetention", summary = "Set or clear how long you keep your own conversations")
    ResponseEntity<ChatRetentionPolicyResponse> save(@CurrentActor IdentityContext identity,
            @Valid @RequestBody ChatRetentionPolicyRequest request) {
        return ResponseEntity.ok()
                .body(new ChatRetentionPolicyResponse(retention.save(identity.actorId(), request.days()).days()));
    }

    @GetMapping("/preview")
    @ApiResponse(responseCode = "200", description = "What the policy would delete", useReturnTypeSchema = true)
    @Operation(operationId = "previewChatRetention",
            summary = "How many of your conversations a retention policy would delete now")
    ResponseEntity<ChatRetentionPreviewResponse> preview(@CurrentActor IdentityContext identity,
            @RequestParam(required = false) @Min(1) @Max(3650) @Nullable Integer days) {
        var preview = retention.preview(identity.actorId(), days);
        return ResponseEntity.ok()
                .body(new ChatRetentionPreviewResponse(preview.days(), preview.affected()));
    }
}
