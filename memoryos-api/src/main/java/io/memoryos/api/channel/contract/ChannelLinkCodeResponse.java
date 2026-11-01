package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelLinkCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** A code for the member to send the bot in a direct message, and when it stops working. */
@Schema(name = "ChannelLinkCodeResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChannelLinkCodeResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {

    public static ChannelLinkCodeResponse from(ChannelLinkCode code) {
        return new ChannelLinkCodeResponse(code.code(), code.expiresAt());
    }
}
