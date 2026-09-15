package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatModelValidationResult")
public record ChatModelValidationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean reachable,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String failureCode
) {}
