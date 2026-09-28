package io.memoryos.api.usage.contract;

import io.memoryos.usage.AiUsageLimit;
import io.memoryos.usage.AiUsageLimitScope;
import io.memoryos.usage.AiUsageLimitService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "AiUsageLimit", description = "A cap on AI spending. A model without a price adds tokens but no cost, so only a token budget binds it")
public record AiUsageLimitResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AiUsageLimitScope scope,
                                   @Nullable UUID groupId, @Nullable String groupName,
                                   @Nullable Long tokenBudget, @Nullable BigDecimal costBudgetUsd,
                                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int periodDays,
                                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
                                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Spent against this limit in its own window; for a per-person limit, the busiest person's spend")
                                   long tokensUsed,
                                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal costUsed) {
    public static AiUsageLimitResponse from(AiUsageLimit value) {
        return from(new AiUsageLimitService.Configured(value, 0, BigDecimal.ZERO));
    }

    public static AiUsageLimitResponse from(AiUsageLimitService.Configured value) {
        AiUsageLimit limit = value.limit();
        return new AiUsageLimitResponse(limit.id(), limit.scope(), limit.groupId(), limit.groupName(), limit.tokenBudget(),
                limit.costBudgetUsd(), limit.periodDays(), limit.enabled(), value.tokensUsed(), value.costUsed());
    }
}
