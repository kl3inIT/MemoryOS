package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ChatLibraryPage")
public record ChatLibraryPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatLibraryFileResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Files matching the filter, not only this page") long totalCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Total size of the files matching the filter") long totalBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore
) {}
