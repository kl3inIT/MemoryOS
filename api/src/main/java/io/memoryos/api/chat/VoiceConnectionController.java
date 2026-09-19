package io.memoryos.api.chat;

import io.memoryos.api.chat.contract.VoiceAvailabilityResponse;
import io.memoryos.api.chat.contract.VoiceConnectionRequest;
import io.memoryos.api.chat.contract.VoiceConnectionResponse;
import io.memoryos.api.chat.contract.VoiceProviderResponse;
import io.memoryos.api.chat.contract.VoiceSelectionRequest;
import io.memoryos.chat.catalog.ProviderCredentials;
import io.memoryos.chat.voice.VoiceConnectionService;
import io.memoryos.chat.voice.VoiceProvider;
import io.memoryos.chat.voice.VoiceProviderClient;
import io.memoryos.iam.identity.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/chat/voice", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Chat Voice")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid voice configuration", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "404", description = "Voice connection unavailable", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "409", description = "Voice connection changed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
@ApiResponse(responseCode = "503", description = "Voice provider unavailable or busy", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(ref = "#/components/schemas/ApiProblem")))
class VoiceConnectionController {
    private final VoiceConnectionService connections;
    private final VoiceProviderClient client;

    VoiceConnectionController(VoiceConnectionService connections, VoiceProviderClient client) {
        this.connections = connections; this.client = client;
    }

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Configured voice availability", useReturnTypeSchema = true)
    @Operation(operationId = "getChatVoiceAvailability", summary = "Read whether speech-to-text and text-to-speech are configured")
    VoiceAvailabilityResponse availability(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        var access = connections.resolve(identity.actorId());
        return new VoiceAvailabilityResponse(access.stt() != null, access.tts() != null);
    }

    @GetMapping("/providers")
    @ApiResponse(responseCode = "200", description = "Implemented voice providers", useReturnTypeSchema = true)
    @Operation(operationId = "listChatVoiceProviders", summary = "List implemented voice providers with suggested models and voices")
    List<VoiceProviderResponse> providers(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        connections.requireManager(identity.actorId());
        return Arrays.stream(VoiceProvider.values()).map(VoiceProviderResponse::from).toList();
    }

    @GetMapping("/connections")
    @ApiResponse(responseCode = "200", description = "Voice connections", useReturnTypeSchema = true)
    @Operation(operationId = "listChatVoiceConnections", summary = "List voice connections for model managers")
    List<VoiceConnectionResponse> list(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity) {
        return connections.list(identity.actorId()).stream().map(VoiceConnectionResponse::from).toList();
    }

    @PutMapping("/connections/{provider}")
    @ApiResponse(responseCode = "200", description = "Verified and saved voice connection", useReturnTypeSchema = true)
    @Operation(operationId = "saveChatVoiceConnection", summary = "Verify and save one voice connection; a first connection may select one function")
    VoiceConnectionResponse save(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable VoiceProvider provider, @Valid @RequestBody VoiceConnectionRequest request) {
        var input = new VoiceConnectionService.Input(request.endpoint(), request.sttModel(), request.ttsModel(), request.ttsVoice(),
                new ProviderCredentials.Change(request.credentialAction(), request.credentialValue()), request.activate(), request.revision());
        var probe = connections.probe(identity.actorId(), provider, input);
        // Onyx parity: a credential the provider rejects is never stored. The request runs outside any transaction.
        if (!provider.requiresKey() || !probe.key().isEmpty()) client.verify(probe);
        return VoiceConnectionResponse.from(connections.save(identity.actorId(), provider, input));
    }

    @DeleteMapping("/connections/{provider}")
    @ApiResponse(responseCode = "204", description = "Voice connection removed from both functions", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteChatVoiceConnection", summary = "Disconnect a voice provider and clear its stored credential")
    void delete(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable VoiceProvider provider, @RequestParam @Min(0) long revision) {
        connections.delete(identity.actorId(), provider, revision);
    }

    @PutMapping("/selection")
    @ApiResponse(responseCode = "204", description = "Voice selection saved", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "selectChatVoiceProvider", summary = "Select the speech-to-text or text-to-speech default; null turns the function off")
    void select(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @Valid @RequestBody VoiceSelectionRequest request) {
        connections.select(identity.actorId(), request.function(), request.provider(), request.model());
    }

    @PostMapping("/connections/{provider}/test")
    @ApiResponse(responseCode = "204", description = "Provider accepted the stored endpoint and credential", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "testChatVoiceConnection", summary = "Verify a stored voice connection with the provider")
    void test(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity, @PathVariable VoiceProvider provider) {
        client.verify(connections.forTest(identity.actorId(), provider));
    }
}
