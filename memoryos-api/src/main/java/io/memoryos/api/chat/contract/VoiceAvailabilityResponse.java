package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

/** Onyx voice status parity: each flag is true when that function has a usable Tenant default. */
public record VoiceAvailabilityResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean sttAvailable,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean ttsAvailable) {}
