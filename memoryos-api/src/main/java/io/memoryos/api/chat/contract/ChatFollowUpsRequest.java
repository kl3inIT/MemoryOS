package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(name = "ChatFollowUpsRequest")
public record ChatFollowUpsRequest(
        @Schema(description = "After each answer, offer three questions to ask next; each costs one more model call") @NotNull Boolean followUpSuggestions,
        @Min(0) long revision) {}
