package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ChatLibraryEntryPage")
public record ChatLibraryEntryPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatLibraryEntryResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Rows matching the filter, not only this page")
        long totalCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore
) {}
