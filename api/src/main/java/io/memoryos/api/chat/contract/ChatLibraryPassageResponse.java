package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChatLibraryPassage")
public record ChatLibraryPassageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String text,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int ordinal
) {}
