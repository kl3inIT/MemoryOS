package io.memoryos.api.channel.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Adds a WhatsApp bot, switched off; it is linked by scanning its code with the bot's phone once switched on. */
@Schema(name = "CreateWhatsAppChannelRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateWhatsAppChannelRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The bot's name here, and its account's")
        @NotBlank @Size(max = 100) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "+84 912 345 678",
                description = "The bot's WhatsApp number in international form; only this number may link it")
        @NotBlank @Size(max = 32) String phone,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"vi", "en"},
                description = "The language of the bot's notices")
        @NotNull @Pattern(regexp = "vi|en") String noticeLanguage) {}
