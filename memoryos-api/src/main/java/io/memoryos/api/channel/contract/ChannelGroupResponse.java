package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelConnectionService.Group;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A group chat a bot has answered in, with the assistant it is given (MEM-273). */
@Schema(name = "ChannelGroupResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChannelGroupResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The platform's id of the group chat")
        String id,
        @Schema(description = "The administrator's own name for the group, shown before the platform's") @Nullable String label,
        @Schema(description = "The group's name on the platform; absent when the platform gives none (Zalo)")
        @Nullable String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "When the bot first answered there")
        Instant firstMessageAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "When the bot last answered there")
        Instant lastMessageAt,
        @Schema(description = "The assistant the group answers with; absent when it answers with the bot's default")
        @Nullable ChannelAssistantResponse assistant) {

    public static ChannelGroupResponse from(Group listed) {
        var group = listed.group();
        return new ChannelGroupResponse(group.chatId(), group.label(), group.name(), group.firstSeenAt(),
                group.lastMessageAt(), listed.assistantId() == null || listed.assistant() == null ? null
                        : new ChannelAssistantResponse(listed.assistantId(), listed.assistant()));
    }
}
