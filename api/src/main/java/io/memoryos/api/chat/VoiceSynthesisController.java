package io.memoryos.api.chat;

import io.memoryos.api.security.CurrentActor;
import io.memoryos.api.chat.contract.VoiceSynthesisRequest;
import io.memoryos.voice.VoiceSynthesisService;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.concurrent.Callable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/chat/voice")
@Tag(name = "Chat Voice")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid text or playback speed")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Chat read authority or CSRF required")
@ApiResponse(responseCode = "404", description = "Membership unavailable")
@ApiResponse(responseCode = "503", description = "No text-to-speech provider, provider unavailable or busy")
class VoiceSynthesisController {
    static final String AUDIO_MPEG = "audio/mpeg";
    private static final Object SPEECH_CLOSE = VoiceSynthesisController.class.getName() + ".speechClose";
    private final VoiceSynthesisService synthesis;

    VoiceSynthesisController(VoiceSynthesisService synthesis) {
        this.synthesis = synthesis;
    }

    @PostMapping(value = "/synthesize", consumes = MediaType.APPLICATION_JSON_VALUE, produces = AUDIO_MPEG)
    @ApiResponse(responseCode = "200", description = "MP3 audio, streamed as the provider produces it",
            content = @Content(mediaType = AUDIO_MPEG, schema = @Schema(type = "string", format = "binary")))
    @Operation(operationId = "synthesizeChatVoice", summary = "Read text aloud with the Tenant's default text-to-speech provider")
    ResponseEntity<StreamingResponseBody> synthesize(@CurrentActor IdentityContext identity,
            @Valid @RequestBody VoiceSynthesisRequest request, HttpServletRequest servletRequest) {
        var speech = synthesis.open(identity.actorId(), request.text(), request.speed());
        closeWhenAsyncEnds(servletRequest, speech);
        StreamingResponseBody body = speech::writeTo;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(AUDIO_MPEG))
                .header("X-Accel-Buffering", "no").body(body);
    }

    /**
     * writeTo closes the speech when the body runs to its end or fails. The async request can also end without it
     * running: a timeout, a client disconnect, or an executor that rejects the task. Spring MVC calls afterCompletion for
     * every such outcome, so the provider and its slot are released however the request ends; closing is idempotent.
     */
    private static void closeWhenAsyncEnds(HttpServletRequest request, AutoCloseable speech) {
        WebAsyncUtils.getAsyncManager(request).registerCallableInterceptor(SPEECH_CLOSE, new CallableProcessingInterceptor() {
            @Override
            public <T> void afterCompletion(NativeWebRequest completed, Callable<T> task) throws Exception {
                speech.close();
            }
        });
    }
}
