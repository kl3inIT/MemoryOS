package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(name = "ChatGroundedRequest")
public record ChatGroundedRequest(
        @Schema(description = "Every turn answers from the organization's documents only") @NotNull Boolean groundedAnswers,
        @Schema(description = "In that mode, a person may still turn Web search on for a turn") @NotNull Boolean groundedAllowWeb,
        @Min(0) long revision) {}
