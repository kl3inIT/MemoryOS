package io.memoryos.api.chat.contract;

import io.memoryos.chat.catalog.ProviderCredentials;
import io.memoryos.chat.voice.VoiceFunction;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** {@code activate} is honored only when the connection is created, selecting it for that function. */
public record VoiceConnectionRequest(@NotNull @Size(max = 2048) String endpoint,
        @NotNull @Size(max = 200) String sttModel, @NotNull @Size(max = 200) String ttsModel,
        @NotNull @Size(max = 200) String ttsVoice, @NotNull ProviderCredentials.Action credentialAction,
        @Nullable @Size(max = 8192) String credentialValue, @Nullable VoiceFunction activate, @Min(0) long revision) {
    @Override public @NonNull String toString() { return "VoiceConnectionRequest[redacted]"; }
}
