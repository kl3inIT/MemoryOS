package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.ChatPreferencesRequest;
import io.memoryos.api.chat.contract.ChatPreferencesResponse;
import io.memoryos.chat.ChatPreferencesService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The current member's own Chat preferences (MEM-145). */
@RestController
@RequestMapping(value = "/api/chat/preferences", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat Preferences")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid preferences", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "CSRF required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Membership unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
class ChatPreferencesController {
    private final ChatPreferencesService preferences;

    ChatPreferencesController(ChatPreferencesService preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "The current member's Chat preferences", useReturnTypeSchema = true)
    @Operation(operationId = "getChatPreferences", summary = "Read the current member's Chat preferences and login profile")
    ChatPreferencesResponse get(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return ChatPreferencesResponse.from(preferences.get(identity.actorId()));
    }

    @PutMapping
    @ApiResponse(responseCode = "200", description = "Saved Chat preferences", useReturnTypeSchema = true)
    @Operation(operationId = "saveChatPreferences", summary = "Replace the current member's Chat preferences")
    ChatPreferencesResponse save(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestBody ChatPreferencesRequest request) {
        return ChatPreferencesResponse.from(preferences.save(identity.actorId(), request.toInput()));
    }
}
