package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatHistoryPage")
public record ChatHistoryPageResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatHistoryEntryResponse> items, @Nullable String nextCursor,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long conversations,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long positive,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long negative) {}
