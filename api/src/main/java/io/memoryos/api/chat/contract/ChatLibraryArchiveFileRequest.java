package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "ChatLibraryArchiveFile")
public record ChatLibraryArchiveFileRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"UPLOAD", "GENERATED", "IMAGE"}) String source,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id
) {}
