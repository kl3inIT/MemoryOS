package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelRegion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;

/** A Lark app to check before it is added. The secret is write-only: no response ever returns it. */
@Schema(name = "ChannelAppRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChannelAppRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Lark international or Feishu")
        @NotNull ChannelRegion region,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "App ID of the custom app, starting with cli_")
        @NotBlank @Size(max = 64) String appId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "App Secret of the custom app")
        @NotBlank @Size(max = 256) String appSecret) {

    @Override public @NonNull String toString() { return "ChannelAppRequest[redacted]"; }
}
