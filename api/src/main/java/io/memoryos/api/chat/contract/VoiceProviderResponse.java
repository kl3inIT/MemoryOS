package io.memoryos.api.chat.contract;

import io.memoryos.chat.voice.VoiceProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record VoiceProviderResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) VoiceProvider provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean requiresKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean requiresEndpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String defaultEndpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> sttModels,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> ttsModels,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> voices) {
    public static VoiceProviderResponse from(VoiceProvider provider) {
        return new VoiceProviderResponse(provider, provider.requiresKey(), provider.requiresEndpoint(), provider.defaultEndpoint(),
                provider.sttModels(), provider.ttsModels(), provider.voices());
    }
}
