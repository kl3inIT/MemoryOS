package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelConnectionService.Listed;
import io.memoryos.channel.ChannelDelivery;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelRegion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A bot as its administrator sees it. Never carries the App Secret or the token. */
@Schema(name = "ChannelResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChannelResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChannelProvider provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(description = "Where a Lark bot's app is hosted; absent for another platform") @Nullable ChannelRegion region,
        @Schema(description = "How a Zalo bot receives; absent for a platform with one way of its own")
        @Nullable ChannelDelivery transport,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The platform's id of the bot's app")
        String appId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String noticeLanguage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The account a group question runs as")
        UUID serviceAccountId,
        @Schema(description = "The bot the platform named at the last check") @Nullable String botName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Scopes the bot needs that the platform refused at the last check")
        List<String> missingScopes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "OFF when switched off; NOT_RUNNING when no process holds it; PAIRING while it shows "
                        + "a code to scan; else CONNECTING, CONNECTED or FAILED")
        Listed.Status status,
        @Schema(description = "The typed code of the last failure to open the connection") @Nullable String failureCode,
        @Schema(description = "When that failure happened") @Nullable Instant failedAt,
        @Schema(description = "Since when the connection has been open") @Nullable Instant connectedAt,
        @Schema(description = "When the bot last received a message") @Nullable Instant lastMessageAt,
        @Schema(description = "The assistant the bot answers with; absent when it answers with the built-in one")
        @Nullable ChannelAssistantResponse assistant,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {

    public static ChannelResponse from(Listed listed) {
        ChannelConnection bot = listed.connection();
        var state = listed.state();
        return new ChannelResponse(bot.id(), bot.provider(), bot.name(), ChannelRegion.of(bot.settings()).orElse(null),
                ChannelDelivery.of(bot.settings()).orElse(null), bot.appId(), bot.noticeLanguage(),
                bot.enabled(), bot.serviceAccount().value(), bot.botName(), bot.missingScopes(), listed.status(),
                state.failureCode(), state.failedAt(), state.openedAt(), state.lastMessageAt(),
                listed.assistantId() == null || listed.assistant() == null ? null
                        : new ChannelAssistantResponse(listed.assistantId(), listed.assistant()), bot.revision(),
                bot.createdAt(), bot.updatedAt());
    }
}
