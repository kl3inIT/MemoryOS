package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelRegion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;

/** Adds a Lark bot, switched off. The secret is write-only: no response ever returns it. */
@Schema(name = "CreateChannelRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateChannelRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The bot's name here, and its account's")
        @NotBlank @Size(max = 100) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Lark international or Feishu")
        @NotNull ChannelRegion region,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "App ID of the custom app, starting with cli_")
        @NotBlank @Size(max = 64) String appId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "App Secret of the custom app")
        @NotBlank @Size(max = 256) String appSecret,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"vi", "en"},
                description = "The language of the bot's notices")
        @NotNull @Pattern(regexp = "vi|en") String noticeLanguage) {

    @Override public @NonNull String toString() { return "CreateChannelRequest[redacted]"; }
}
