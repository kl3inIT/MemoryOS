package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatLibraryFileChange")
public record ChatLibraryFileChangeRequest(
        @Schema(description = "A new name; the file keeps its extension", maxLength = 255) @Nullable String filename,
        @Schema(description = "Star or unstar the file") @Nullable Boolean favorite
) {}
