package io.memoryos.api.usage.contract;

import io.memoryos.usage.AiUsageLimit;
import io.memoryos.usage.AiUsageLimitScope;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "AiUsageLimitRequest")
public record AiUsageLimitRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) AiUsageLimitScope scope,
                                  @Nullable UUID groupId, @Nullable Long tokenBudget, @Nullable BigDecimal costBudgetUsd,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int periodDays,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled) {
    public AiUsageLimit toLimit(UUID id) {
        return new AiUsageLimit(id, scope, groupId, null, tokenBudget, costBudgetUsd, periodDays, enabled);
    }
}
