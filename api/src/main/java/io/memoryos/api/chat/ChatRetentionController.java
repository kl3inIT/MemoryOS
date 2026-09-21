package io.memoryos.api.chat;

import io.memoryos.chat.preferences.ChatRetentionService;
import io.memoryos.iam.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@ApiResponse(responseCode = "400", description = "The number of days is outside 1 to 3650", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "CSRF requirement not met", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Chat is unavailable or the person is not a member", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
class ChatRetentionController {
    private final ChatRetentionService retention;

    ChatRetentionController(ChatRetentionService retention) { this.retention = retention; }

    @Schema(name = "ChatRetentionPolicy")
    record PolicyResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"},
            description = "Days of inactivity after which one of your conversations is deleted; null keeps them until you delete them")
            @Nullable Integer days) {}

    @Schema(name = "ChatRetentionInput")
    record PolicyRequest(@Schema(description = "Days of inactivity, 1 to 3650; leave it out to keep conversations until you delete them")
            @Min(1) @Max(3650) @Nullable Integer days) {}

    @Schema(name = "ChatRetentionPreview")
    record PreviewResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"}) @Nullable Integer days,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "How many of your conversations this policy would delete now") long affected) {}

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Your retention policy", useReturnTypeSchema = true)
    @Operation(operationId = "getChatRetention", summary = "Read how long you keep your own conversations")
    ResponseEntity<PolicyResponse> read(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new PolicyResponse(retention.read(identity.actorId()).days()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "The policy after the change", useReturnTypeSchema = true)
    @Operation(operationId = "saveChatRetention", summary = "Set or clear how long you keep your own conversations")
    ResponseEntity<PolicyResponse> save(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Valid @RequestBody PolicyRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new PolicyResponse(retention.save(identity.actorId(), request.days()).days()));
    }

    @GetMapping("/preview")
    @ApiResponse(responseCode = "200", description = "What the policy would delete", useReturnTypeSchema = true)
    @Operation(operationId = "previewChatRetention",
            summary = "How many of your conversations a retention policy would delete now")
    ResponseEntity<PreviewResponse> preview(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(required = false) @Min(1) @Max(3650) @Nullable Integer days) {
        var preview = retention.preview(identity.actorId(), days);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new PreviewResponse(preview.days(), preview.affected()));
    }
}
