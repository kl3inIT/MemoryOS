package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatLibraryDocumentPage")
public record ChatLibraryDocumentPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatLibraryEntryResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                description = "Pass as cursor, with the same filters and sort, for the next page; null on the last")
        @Nullable String nextCursor
) {}
