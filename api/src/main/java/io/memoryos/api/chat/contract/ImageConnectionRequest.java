package io.memoryos.api.chat.contract;

import io.memoryos.chat.catalog.ProviderCredentials;
import jakarta.validation.constraints.*;
import org.jspecify.annotations.Nullable;

public record ImageConnectionRequest(@NotNull @Size(max = 2048) String endpoint,
        @NotNull @Size(max = 200) String model, @NotNull ProviderCredentials.Action credentialAction,
        @Nullable @Size(max = 8192) String credentialValue, @Min(0) long revision) {
    @Override public @org.jspecify.annotations.NonNull String toString() { return "ImageConnectionRequest[redacted]"; }
}
