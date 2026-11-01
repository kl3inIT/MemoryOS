package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatLibraryUsage")
public record ChatLibraryUsageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long usedBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long fileCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"}, format = "int64",
                description = "The storage limit that applies to the caller; null means no limit")
        @Nullable Long limitBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Used bytes per category")
        List<ChatLibraryCategoryUsageResponse> byCategory
) {}
