package io.memoryos.api.chat.contract;

import io.memoryos.voice.VoiceConnectionService;
import io.memoryos.voice.VoiceProvider;
import io.swagger.v3.oas.annotations.media.Schema;

public record VoiceConnectionResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) VoiceProvider provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String endpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sttModel,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ttsModel,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ttsVoice,
        boolean credentialConfigured, boolean sttActive, boolean ttsActive, long revision) {
    public static VoiceConnectionResponse from(VoiceConnectionService.View view) {
        return new VoiceConnectionResponse(view.provider(), view.endpoint(), view.sttModel(), view.ttsModel(), view.ttsVoice(),
                view.credentialConfigured(), view.sttActive(), view.ttsActive(), view.revision());
    }
}
