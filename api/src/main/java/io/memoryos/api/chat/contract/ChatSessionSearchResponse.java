package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ChatSessionSearchPage")
public record ChatSessionSearchResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatSessionResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) {}
