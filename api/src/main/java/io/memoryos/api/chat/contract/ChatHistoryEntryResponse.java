package io.memoryos.api.chat.contract;

import io.memoryos.chat.history.ChatHistoryFeedback;
import io.memoryos.chat.history.ChatHistoryService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatHistoryEntry", description = "One conversation. The person is absent when the Tenant hides who asked")
public record ChatHistoryEntryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Nullable UUID actorId, @Nullable String person, @Nullable String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Nullable String question, @Nullable String answer, @Nullable String modelName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long messages,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatHistoryFeedback feedback,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The owner deleted this conversation; it is kept until retention removes it")
        boolean deleted,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt
) {
    public static ChatHistoryEntryResponse from(ChatHistoryService.Conversation value) {
        return new ChatHistoryEntryResponse(value.id(), value.actorId(), value.person(), value.email(), value.title(),
                value.question(), value.answer(), value.modelName(), value.messages(), value.feedback(),
                value.deleted(), value.updatedAt());
    }
}
