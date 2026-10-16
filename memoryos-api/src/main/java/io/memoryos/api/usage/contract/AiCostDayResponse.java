package io.memoryos.api.usage.contract;

import io.memoryos.usage.AiCostDay;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(name = "AiCostDay", description = "One UTC day of one series: a data boundary (INTERNAL, EXTERNAL, NONE), a model name or ALL")
public record AiCostDayResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate day,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String series,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal cost,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long calls,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long inputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long outputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long cacheReadTokens
) {
    public static AiCostDayResponse from(AiCostDay value) {
        return new AiCostDayResponse(value.day(), value.key(), value.cost(), value.calls(), value.inputTokens(),
                value.outputTokens(), value.cacheReadTokens());
    }
}
