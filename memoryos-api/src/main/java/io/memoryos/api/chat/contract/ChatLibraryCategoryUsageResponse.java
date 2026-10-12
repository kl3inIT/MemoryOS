package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChatLibraryCategoryUsage")
public record ChatLibraryCategoryUsageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"DOCUMENT", "SPREADSHEET", "IMAGE", "PRESENTATION", "OTHER"}) String category,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long usedBytes
) {}
