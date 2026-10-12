package io.memoryos.api.chat.contract;

import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ProviderCredentials;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record SystemOneConnectionRequest(
        @NotBlank @Size(max = 80) String name,
        @Schema(description = "The base address of a gateway or self-hosted server, a Cloudflare account ID, or empty "
                + "for a type with a fixed address")
        @NotNull @Size(max = 2048) String endpoint,
        @Schema(description = "Empty takes the type's default model")
        @NotNull @Size(max = 200) String model,
        @NotNull ProviderCredentials.Action credentialAction,
        @Nullable @Size(max = 8192) String credentialValue,
        @NotNull DataBoundary dataBoundary,
        @Schema(description = "USD per million input tokens; omitted when unknown")
        @Nullable @DecimalMin("0") @DecimalMax("1000000") Double inputPrice,
        @Min(0) long revision) {
    @Override public @NonNull String toString() { return "SystemOneConnectionRequest[redacted]"; }
}
