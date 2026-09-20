package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatSession;
import io.memoryos.chat.preferences.ReasoningEffort;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatSession")
public record ChatSessionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID personaId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID rootMessageId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid") @Nullable UUID projectId,
        // OFF, LOW, MEDIUM or HIGH, or null when nothing is pinned. A nullable enum generates a union that cannot
        // express the null, so the level travels as a string here.
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                description = "Pinned reasoning level: OFF, LOW, MEDIUM or HIGH")
        @Nullable String reasoningEffort) {
    public static ChatSessionResponse from(ChatSession session) {
        return new ChatSessionResponse(session.id(), session.personaId(), session.rootMessageId(), session.title(),
                session.createdAt(), session.updatedAt(), session.projectId(), session.reasoningEffort() == null ? null : session.reasoningEffort().name());
    }
}
