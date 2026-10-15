package io.memoryos.api.channel.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;

/** A Zalo bot to check before it is added. The token is write-only: no response ever returns it. */
@Schema(name = "ZaloBotRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ZaloBotRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The bot's token from Zalo Bot Creator")
        @NotBlank @Size(max = 256) String token) {

    @Override public @NonNull String toString() { return "ZaloBotRequest[redacted]"; }
}
