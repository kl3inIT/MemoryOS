package io.memoryos.api.ocr.contract;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Unsaved connection details to test; a missing credentialValue falls back to the stored key. */
public record OcrConnectionTestRequest(@NotNull @Size(max = 2048) String endpoint,
        @Nullable @Size(max = 8192) String credentialValue) {
    @Override public @NonNull String toString() { return "OcrConnectionTestRequest[redacted]"; }
}
