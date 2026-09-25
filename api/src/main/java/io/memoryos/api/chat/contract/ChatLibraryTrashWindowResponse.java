package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChatLibraryTrashWindow")
public record ChatLibraryTrashWindowResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Days a deleted file stays restorable; 0 releases its bytes at once") long days
) {}
