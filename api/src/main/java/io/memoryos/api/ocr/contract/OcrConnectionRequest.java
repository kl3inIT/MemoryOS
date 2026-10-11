package io.memoryos.api.ocr.contract;

import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ProviderCredentials;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record OcrConnectionRequest(@NotNull @Size(max = 2048) String endpoint,
        @NotNull ProviderCredentials.Action credentialAction, @Nullable @Size(max = 8192) String credentialValue,
        @NotNull DataBoundary dataBoundary, @Min(0) long revision) {
    @Override public @NonNull String toString() { return "OcrConnectionRequest[redacted]"; }
}
