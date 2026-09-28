package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ChatLibraryArchiveRequest")
public record ChatLibraryArchiveRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100,
                description = "The files to pack, at most 100 and at most 30 MiB together") List<ChatLibraryArchiveFileRequest> files
) {}
