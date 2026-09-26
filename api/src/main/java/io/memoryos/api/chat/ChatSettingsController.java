package io.memoryos.api.chat;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.chat.contract.ChatGroundedRequest;
import io.memoryos.api.chat.contract.ChatGuardrailTopic;
import io.memoryos.api.chat.contract.ChatGuardrailsRequest;
import io.memoryos.api.chat.contract.ChatGuardrailsResponse;
import io.memoryos.api.chat.contract.ChatHistoryVisibilityRequest;
import io.memoryos.api.chat.contract.ChatSettingsRequest;
import io.memoryos.api.chat.contract.ChatSettingsResponse;
import io.memoryos.chat.ChatSettingsService;
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
@RequestMapping(value = "/api/chat/settings", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat Settings")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid Chat settings")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required")
@ApiResponse(responseCode = "404", description = "Chat unavailable")
@ApiResponse(responseCode = "409", description = "Chat settings changed")
class ChatSettingsController {
    private final ChatSettingsService settings;

    ChatSettingsController(ChatSettingsService settings) { this.settings = settings; }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Tenant Chat settings", useReturnTypeSchema = true)
    @Operation(operationId = "getChatSettings", summary = "Read Tenant Chat settings, such as whether Deep research is offered")
    ChatSettingsResponse read(@CurrentActor IdentityContext identity) {
        return ChatSettingsResponse.from(settings.read(identity.actorId()));
    }

    @PutMapping
    @ApiResponse(responseCode = "200", description = "Saved Tenant Chat settings", useReturnTypeSchema = true)
    @Operation(operationId = "saveChatSettings", summary = "Change Tenant Chat settings for model managers")
    ChatSettingsResponse save(@CurrentActor IdentityContext identity,
            @Valid @RequestBody ChatSettingsRequest request) {
        return ChatSettingsResponse.from(settings.save(identity.actorId(), request.deepResearchEnabled(), request.revision()));
    }

    @PutMapping("/history-visibility")
    @ApiResponse(responseCode = "200", description = "Saved Tenant Chat settings", useReturnTypeSchema = true)
    @Operation(operationId = "saveChatHistoryVisibility", summary = "Choose who may read other people's conversations; requires model management")
    ChatSettingsResponse saveHistoryVisibility(@CurrentActor IdentityContext identity,
            @Valid @RequestBody ChatHistoryVisibilityRequest request) {
        return ChatSettingsResponse.from(
                settings.saveHistoryVisibility(identity.actorId(), request.visibility(), request.revision()));
    }

    @PutMapping("/grounded")
    @ApiResponse(responseCode = "200", description = "Saved Tenant Chat settings", useReturnTypeSchema = true)
    @Operation(operationId = "saveChatGrounded", summary = "Answer from the organization's documents only; requires model management")
    ChatSettingsResponse saveGrounded(@CurrentActor IdentityContext identity, @Valid @RequestBody ChatGroundedRequest request) {
        return ChatSettingsResponse.from(settings.saveGrounded(identity.actorId(), request.groundedAnswers(),
                request.groundedAllowWeb(), request.revision()));
    }

    @GetMapping("/guardrails")
    @ApiResponse(responseCode = "200", description = "Sensitive-topic guardrails", useReturnTypeSchema = true)
    @Operation(operationId = "getChatGuardrails", summary = "Read the sensitive topics and blocked phrases; requires model management")
    ChatGuardrailsResponse guardrails(@CurrentActor IdentityContext identity) {
        return ChatGuardrailsResponse.from(settings.guardrails(identity.actorId()));
    }

    @PutMapping("/guardrails")
    @ApiResponse(responseCode = "200", description = "Saved sensitive-topic guardrails", useReturnTypeSchema = true)
    @Operation(operationId = "saveChatGuardrails", summary = "Change the sensitive topics and blocked phrases; requires model management")
    ChatGuardrailsResponse saveGuardrails(@CurrentActor IdentityContext identity, @Valid @RequestBody ChatGuardrailsRequest request) {
        return ChatGuardrailsResponse.from(settings.saveGuardrails(identity.actorId(),
                request.topics().stream().map(ChatGuardrailTopic::setting).toList(), request.blockedPhrases(),
                request.blockedPhraseMessage(), request.revision()));
    }

}
