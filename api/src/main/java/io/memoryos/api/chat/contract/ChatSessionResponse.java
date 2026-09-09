package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatSession;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "ChatSession")
public record ChatSessionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID personaId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID rootMessageId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt) {
    public static ChatSessionResponse from(ChatSession session) {
        return new ChatSessionResponse(session.id(), session.personaId(), session.rootMessageId(), session.title(),
                session.createdAt(), session.updatedAt());
    }
}
