package io.memoryos.api.chat.contract;

import io.memoryos.library.LibraryFile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "ChatLibraryFileUsage")
public record ChatLibraryFileUsageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"AGENT", "PROJECT"}) String kind,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name
) {

    public static ChatLibraryFileUsageResponse from(LibraryFile.Usage usage) {
        return new ChatLibraryFileUsageResponse(usage.kind().name(), usage.id(), usage.name());
    }
}
