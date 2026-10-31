package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelConnectionService.Answering;
import io.memoryos.channel.ChannelProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A bot an assistant answers with, as anyone who may use the assistant sees it: no app, secret or account. It answers
 * with the assistant by default, in the group chats listed, or both (MEM-273).
 */
@Schema(name = "AssistantChannelResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record AssistantChannelResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChannelProvider provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(description = "The bot the platform named at the last check") @Nullable String botName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether the bot answers anyone, so whatever the assistant is given is read by anyone who "
                        + "messages it")
        boolean publicAccess,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether the assistant is the bot's default, used where no group chat has its own")
        boolean byDefault,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The bot's group chats given the assistant as their own")
        List<AssistantChannelGroupResponse> groups) {

    public static AssistantChannelResponse from(Answering answering) {
        ChannelConnection bot = answering.connection();
        return new AssistantChannelResponse(bot.id(), bot.provider(), bot.name(), bot.botName(), bot.enabled(),
                bot.publicAccess(), answering.byDefault(),
                answering.groups().stream().map(AssistantChannelGroupResponse::from).toList());
    }
}
