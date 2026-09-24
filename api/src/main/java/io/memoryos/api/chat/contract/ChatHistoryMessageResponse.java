package io.memoryos.api.chat.contract;

import io.memoryos.chat.history.ChatHistoryMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatHistoryMessage", description = "One message; citations are named, and opening one uses the reader's own Source authority")
public record ChatHistoryMessageResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String role,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content,
                                         @Nullable String modelName,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
                                         @Nullable Boolean positive, @Nullable String comment,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> citations) {
    public static ChatHistoryMessageResponse from(ChatHistoryMessage value) {
        return new ChatHistoryMessageResponse(value.id(), value.role(), value.content(), value.modelName(), value.createdAt(),
                value.positive(), value.comment(), value.citations());
    }
}
