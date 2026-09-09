package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatMessage")
public record ChatMessageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sessionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid") @Nullable UUID parentMessageId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid") @Nullable UUID latestChildMessageId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"USER", "ASSISTANT"}) String role,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"COMPLETED", "RUNNING", "CANCELED", "FAILED"}) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time") @Nullable Instant finishedAt) {
    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(message.id(), message.sessionId(), message.parentMessageId(),
                message.latestChildMessageId(), message.role().name(), message.content(), message.status().name(),
                message.createdAt(), message.finishedAt());
    }
}
