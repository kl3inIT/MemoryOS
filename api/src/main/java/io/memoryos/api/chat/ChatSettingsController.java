package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ChatSettingsRequest;
import io.memoryos.api.chat.contract.ChatSettingsResponse;
import io.memoryos.chat.ChatSettingsService;
import io.memoryos.iam.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat/settings", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat Settings")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid Chat settings", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Chat unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "409", description = "Chat settings changed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
class ChatSettingsController {
    private final ChatSettingsService settings;

    ChatSettingsController(ChatSettingsService settings) { this.settings = settings; }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Tenant Chat settings", useReturnTypeSchema = true)
    @Operation(operationId = "getChatSettings", summary = "Read Tenant Chat settings, such as whether Deep research is offered")
    ChatSettingsResponse read(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return ChatSettingsResponse.from(settings.read(identity.actorId()));
    }

    @PutMapping
    @ApiResponse(responseCode = "200", description = "Saved Tenant Chat settings", useReturnTypeSchema = true)
    @Operation(operationId = "saveChatSettings", summary = "Change Tenant Chat settings for model managers")
    ChatSettingsResponse save(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Valid @RequestBody ChatSettingsRequest request) {
        return ChatSettingsResponse.from(settings.save(identity.actorId(), request.deepResearchEnabled(), request.revision()));
    }

    @Schema(name = "ChatRetentionRequest")
    record RetentionRequest(
            @Schema(description = "Days of inactivity after which a conversation is deleted; leave it out to clear the policy")
            @Min(1) @Max(3650) @Nullable Integer chatRetentionDays,
            @Min(0) long revision) {}

    @Schema(name = "ChatRetentionPreview")
    record RetentionPreviewResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"}) @Nullable Integer days,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "How many conversations this policy would delete now") long affected) {}

    @PutMapping("/retention")
    @ApiResponse(responseCode = "200", description = "Saved retention policy", useReturnTypeSchema = true)
    @Operation(operationId = "saveChatRetention",
            summary = "Bound how long this Tenant keeps conversations nobody has touched (Onyx maximum_chat_retention_days)")
    ChatSettingsResponse saveRetention(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Valid @RequestBody RetentionRequest request) {
        return ChatSettingsResponse.from(
                settings.saveRetention(identity.actorId(), request.chatRetentionDays(), request.revision()));
    }

    @GetMapping("/retention/preview")
    @ApiResponse(responseCode = "200", description = "What the policy would delete", useReturnTypeSchema = true)
    @Operation(operationId = "previewChatRetention",
            summary = "How many conversations a retention policy would delete now")
    RetentionPreviewResponse previewRetention(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestParam(required = false) @Min(1) @Max(3650) @Nullable Integer days) {
        var preview = settings.previewRetention(identity.actorId(), days);
        return new RetentionPreviewResponse(preview.days(), preview.affected());
    }
}
