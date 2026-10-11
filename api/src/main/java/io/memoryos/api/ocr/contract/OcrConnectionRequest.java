package io.memoryos.api.ocr.contract;

import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ProviderCredentials;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A service connection carries its address and data boundary; a connection that reads with a model carries
 * {@code modelConfigurationId} instead, with an empty address, and takes its boundary from the model's provider.
 */
public record OcrConnectionRequest(@NotNull @Size(max = 2048) String endpoint,
        @NotNull ProviderCredentials.Action credentialAction, @Nullable @Size(max = 8192) String credentialValue,
        @Nullable DataBoundary dataBoundary, @Nullable UUID modelConfigurationId, @Min(0) long revision) {
    @Override public @NonNull String toString() { return "OcrConnectionRequest[redacted]"; }
}
