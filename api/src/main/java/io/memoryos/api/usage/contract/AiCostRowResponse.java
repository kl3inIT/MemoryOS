package io.memoryos.api.usage.contract;

import io.memoryos.usage.AiCostRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

@Schema(name = "AiCostRow", description = "A ranked row: a person (key SYSTEM for work without a person), Group, model, task or provider")
public record AiCostRowResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String key,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String detail,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long calls,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long unknownCostCalls,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long inputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long outputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal cost
) {
    public static AiCostRowResponse from(AiCostRow value) {
        return new AiCostRowResponse(value.key(), value.label(), value.detail(), value.calls(), value.unknownCostCalls(),
                value.inputTokens(), value.outputTokens(), value.cost());
    }
}
