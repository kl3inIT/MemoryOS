package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelPairing;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

/** The code a bot being linked shows, to draw as a QR code and scan with the bot's phone before it expires. */
@Schema(name = "ChannelPairingResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChannelPairingResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The QR code's content") String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {

    public static ChannelPairingResponse from(ChannelPairing pairing) {
        return new ChannelPairingResponse(pairing.code(), pairing.expiresAt());
    }

    @Override public @NonNull String toString() { return "ChannelPairingResponse[code=redacted, expiresAt=" + expiresAt + "]"; }
}
