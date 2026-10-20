package io.memoryos.api.channel.contract;

import io.memoryos.channel.ChannelConnectionService.Group;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** A group chat a bot is known in, with the assistant it is given (MEM-273) and whether the bot may answer there. */
@Schema(name = "ChannelGroupResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ChannelGroupResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The platform's id of the group chat")
        String id,
        @Schema(description = "The administrator's own name for the group, shown before the platform's") @Nullable String label,
        @Schema(description = "The group's name on the platform; absent when the platform gives none (Zalo)")
        @Nullable String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "When the group became known: the bot's first answer there, or on Lark the bot joining it")
        Instant firstMessageAt,
        @Schema(description = "When the bot last answered there; absent for a Lark group nobody has asked in yet")
        @Nullable Instant lastMessageAt,
        @Schema(description = "The assistant the group answers with; absent when it answers with the bot's default")
        @Nullable ChannelAssistantResponse assistant,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Whether the bot answers there only once an "
                + "administrator allows it, as on Zalo, which cannot tell who is in a group (MEM-285)")
        Permission permission) {

    /** A group of Lark or WhatsApp needs no permission; a Zalo group is allowed or waits for it. */
    public enum Permission { NOT_NEEDED, ALLOWED, WAITING }

    public static ChannelGroupResponse from(Group listed) {
        var group = listed.group();
        var permission = !listed.needsPermission() ? Permission.NOT_NEEDED
                : group.allowedAt() != null ? Permission.ALLOWED : Permission.WAITING;
        return new ChannelGroupResponse(group.chatId(), group.label(), group.name(), group.firstSeenAt(),
                group.lastMessageAt(), listed.assistantId() == null || listed.assistant() == null ? null
                        : new ChannelAssistantResponse(listed.assistantId(), listed.assistant()), permission);
    }
}
