package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelVerification;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** An app the platform accepts: the bot it has, and the scopes the bot needs that the platform still refuses. */
@Schema(name = "ChannelAppCheckResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChannelAppCheckResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String botName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> missingScopes) {

    public static ChannelAppCheckResponse from(ChannelVerification verification) {
        return new ChannelAppCheckResponse(verification.botName(), verification.missingScopes().stream().sorted().toList());
    }
}
