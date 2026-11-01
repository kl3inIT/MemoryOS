package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelGroup;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** A group chat given an assistant, as anyone who may use the assistant sees it. */
@Schema(name = "AssistantChannelGroupResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record AssistantChannelGroupResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The platform's id of the group chat")
        String id,
        @Schema(description = "The administrator's own name for the group") @Nullable String label,
        @Schema(description = "The group's name on the platform; absent when the platform gives none") @Nullable String name) {

    public static AssistantChannelGroupResponse from(ChannelGroup group) {
        return new AssistantChannelGroupResponse(group.chatId(), group.label(), group.name());
    }
}
