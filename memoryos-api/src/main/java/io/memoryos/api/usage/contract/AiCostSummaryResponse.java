package io.memoryos.api.usage.contract;

import io.memoryos.usage.AiCostTotals;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(name = "AiCostSummary", description = "Known costs in USD; calls without a price or reported usage are counted in unknownCostCalls, never as zero")
public record AiCostSummaryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal cost,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal externalCost,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long calls,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long unknownCostCalls,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long inputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long outputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long cacheReadTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long imageCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal audioSeconds,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long activePeople
) {
    public static AiCostSummaryResponse from(AiCostTotals value) {
        return new AiCostSummaryResponse(value.cost(), value.externalCost(), value.calls(), value.unknownCostCalls(),
                value.inputTokens(), value.outputTokens(), value.cacheReadTokens(), value.imageCount(), value.audioSeconds(),
                value.activePeople());
    }
}
