package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ChatHistoryTranscript")
public record ChatHistoryTranscriptResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatHistoryEntryResponse conversation,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatHistoryMessageResponse> messages
) {}
