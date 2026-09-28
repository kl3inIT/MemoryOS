package io.memoryos.api.chat.contract;

import io.memoryos.voice.VoiceFunction;
import io.memoryos.voice.VoiceProvider;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** A null provider turns the function off. {@code model} chooses a Text-to-Speech model of the selected provider. */
public record VoiceSelectionRequest(@NotNull VoiceFunction function, @Nullable VoiceProvider provider,
        @Nullable @Size(max = 200) String model) {}
