package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@Schema(name = "ChatMemoryInput")
public record ChatMemoryRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The whole memory, at most 4000 characters") @NotNull @Size(max = 4000) String content,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The revision this text was edited from") @PositiveOrZero long revision) {}
