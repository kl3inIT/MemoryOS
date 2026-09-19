package io.memoryos.api.chat.contract;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Plain answer text to read aloud and the member's playback speed. */
public record VoiceSynthesisRequest(@NotBlank @Size(max = 32000) String text,
        @NotNull @DecimalMin("0.5") @DecimalMax("2.0") Double speed) {
    @Override
    public String toString() {
        return "VoiceSynthesisRequest[text=<redacted>, speed=" + speed + "]";
    }
}
