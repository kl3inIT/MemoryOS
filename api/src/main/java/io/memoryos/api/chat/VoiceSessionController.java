package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.VoiceSettingsRequest;
import io.memoryos.api.chat.contract.VoiceSettingsResponse;
import io.memoryos.api.chat.contract.VoiceTicketPurpose;
import io.memoryos.api.chat.contract.VoiceTicketRequest;
import io.memoryos.api.chat.contract.VoiceTicketResponse;
import io.memoryos.chat.VoiceSettingsService;
import io.memoryos.voice.VoiceSynthesisService;
import io.memoryos.voice.VoiceTranscriptionService;
import io.memoryos.iam.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat/voice", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat Voice")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid voice request", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Voice authority or CSRF required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Membership unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "503", description = "Too many voice tickets", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
class VoiceSessionController {
    private final VoiceTicketStore tickets;
    private final VoiceTranscriptionService transcription;
    private final VoiceSynthesisService synthesis;
    private final VoiceSettingsService settings;

    VoiceSessionController(VoiceTicketStore tickets, VoiceTranscriptionService transcription, VoiceSynthesisService synthesis,
            VoiceSettingsService settings) {
        this.tickets = tickets; this.transcription = transcription; this.synthesis = synthesis; this.settings = settings;
    }

    @PostMapping("/tickets")
    @ApiResponse(responseCode = "200", description = "Single-use voice WebSocket ticket", useReturnTypeSchema = true)
    @Operation(operationId = "createChatVoiceTicket",
            summary = "Issue a 60-second single-use ticket for the transcription or read-aloud voice WebSocket")
    VoiceTicketResponse ticket(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestBody(required = false) @Nullable VoiceTicketRequest request) {
        var purpose = request == null || request.purpose() == null ? VoiceTicketPurpose.TRANSCRIBE : request.purpose();
        if (purpose == VoiceTicketPurpose.SYNTHESIZE) synthesis.requireAccess(identity.actorId());
        else transcription.requireAccess(identity.actorId());
        var issued = tickets.issue(identity.actorId(), purpose);
        return new VoiceTicketResponse(issued.value(), issued.expiresAt());
    }

    @GetMapping("/settings")
    @ApiResponse(responseCode = "200", description = "The current member's voice settings", useReturnTypeSchema = true)
    @Operation(operationId = "getChatVoiceSettings", summary = "Read the current member's voice settings")
    VoiceSettingsResponse settings(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return VoiceSettingsResponse.from(settings.get(identity.actorId()));
    }

    @PatchMapping("/settings")
    @ApiResponse(responseCode = "200", description = "Updated voice settings", useReturnTypeSchema = true)
    @Operation(operationId = "updateChatVoiceSettings", summary = "Change the current member's voice settings; absent values are kept")
    VoiceSettingsResponse update(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Valid @RequestBody VoiceSettingsRequest request) {
        return VoiceSettingsResponse.from(settings.update(identity.actorId(), request.autoSend(), request.autoPlayback(),
                request.playbackSpeed()));
    }
}
