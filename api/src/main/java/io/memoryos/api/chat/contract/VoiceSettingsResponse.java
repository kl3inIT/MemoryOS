package io.memoryos.api.chat.contract;

import io.memoryos.chat.preferences.VoiceSettings;
import io.swagger.v3.oas.annotations.media.Schema;

public record VoiceSettingsResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean autoSend,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean autoPlayback,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double playbackSpeed) {
    public static VoiceSettingsResponse from(VoiceSettings settings) {
        return new VoiceSettingsResponse(settings.autoSend(), settings.autoPlayback(), settings.playbackSpeed());
    }
}
