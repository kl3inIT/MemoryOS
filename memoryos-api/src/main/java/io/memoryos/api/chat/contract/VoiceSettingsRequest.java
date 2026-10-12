package io.memoryos.api.chat.contract;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.jspecify.annotations.Nullable;

/** Absent values keep the member's current setting. */
public record VoiceSettingsRequest(@Nullable Boolean autoSend, @Nullable Boolean autoPlayback,
        @Nullable @DecimalMin("0.5") @DecimalMax("2.0") Double playbackSpeed) {}
