package io.memoryos.api.chat.contract;

import io.memoryos.chat.AgentOwner;
import io.memoryos.chat.ChatAssistant;
import io.memoryos.chat.ChatAssistantTopic;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatAssistant",
        description = "One row of the caller's list of assistants: the built-in assistant first, then their pins in order")
public record ChatAssistantResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                description = "The role line under the name; null when unset or the assistant is not usable")
        @Nullable String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Empty when the assistant is not usable")
        String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Seed of the assistant's generated face")
        String avatarSeed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "An uploaded image replaces the face; false when the assistant is not usable")
        boolean hasAvatar,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean builtin,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                description = "The owning group's name, else the owner's name or email; null for the built-in assistant or an unusable one")
        @Nullable String ownerName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "False once the assistant was deleted or is no longer shared with the caller; its topics stay readable")
        boolean usable,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"object", "null"},
                description = "The caller's newest web topic with this assistant; null before the first")
        @Nullable Topic latestTopic) {

    @Schema(name = "ChatAssistantTopic")
    public record Topic(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sessionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "When its last question or finished answer was written")
            Instant lastMessageAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "That message as one line of plain text, at most 120 characters; empty before anyone wrote")
            String preview,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean replyRunning) {
        static Topic from(ChatAssistantTopic topic) {
            return new Topic(topic.sessionId(), topic.title(), topic.lastMessageAt(), topic.preview(), topic.replyRunning());
        }
    }

    public static ChatAssistantResponse from(ChatAssistant assistant) {
        return new ChatAssistantResponse(assistant.id(), assistant.name(), assistant.title(), assistant.description(),
                assistant.avatarSeed(), assistant.hasAvatar(), assistant.builtin(), ownerName(assistant.owner()),
                assistant.usable(), assistant.latestTopic() == null ? null : Topic.from(assistant.latestTopic()));
    }

    private static @Nullable String ownerName(AgentOwner owner) {
        if (owner.group() != null) return owner.group().name();
        if (owner.actor() == null) return null;
        return owner.actor().name() != null ? owner.actor().name() : owner.actor().email();
    }
}
