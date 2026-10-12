package io.memoryos.api.chat.contract;

import jakarta.validation.constraints.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Unsaved connection details to probe; a missing credentialValue falls back to the stored key. */
public record ImageConnectionTestRequest(@NotNull @Size(max = 2048) String endpoint,
        @NotNull @Size(max = 200) String model, @Nullable @Size(max = 8192) String credentialValue) {
    @Override public @NonNull String toString() { return "ImageConnectionTestRequest[redacted]"; }
}
