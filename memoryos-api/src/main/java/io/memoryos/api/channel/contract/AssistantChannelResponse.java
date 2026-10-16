package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A bot bound to an assistant, as anyone who may use the assistant sees it: no app, secret or account. */
@Schema(name = "AssistantChannelResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record AssistantChannelResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChannelProvider provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(description = "The bot the platform named at the last check") @Nullable String botName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled) {

    public static AssistantChannelResponse from(ChannelConnection bot) {
        return new AssistantChannelResponse(bot.id(), bot.provider(), bot.name(), bot.botName(), bot.enabled());
    }
}
