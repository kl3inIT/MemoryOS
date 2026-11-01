package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelLink;
import io.memoryos.channel.ChannelProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A bot of the Tenant that takes a linked account; {@code linkedAt} is absent while the member has not linked one. */
@Schema(name = "ChannelLinkResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChannelLinkResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID connectionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String botName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChannelProvider provider,
        @Nullable Instant linkedAt) {

    public static ChannelLinkResponse from(ChannelLink link) {
        return new ChannelLinkResponse(link.connectionId(), link.botName(), link.provider(), link.linkedAt());
    }
}
