package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelDelivery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Adds a Zalo bot, switched off. The token is write-only: no response ever returns it. */
@Schema(name = "CreateZaloChannelRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateZaloChannelRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The bot's name here, and its account's")
        @NotBlank @Size(max = 100) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The bot's token from Zalo Bot Creator")
        @NotBlank @Size(max = 256) String token,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"vi", "en"},
                description = "The language of the bot's notices")
        @NotNull @Pattern(regexp = "vi|en") String noticeLanguage,
        @Schema(description = "How the bot receives: WEBHOOK where the deployment has a public address, which is the "
                + "default there, else POLLING")
        @Nullable ChannelDelivery transport) {

    @Override public @NonNull String toString() { return "CreateZaloChannelRequest[redacted]"; }
}
