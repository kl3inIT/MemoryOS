package io.memoryos.api.usage.contract;

import io.memoryos.usage.AiUsageLimitScope;
import io.memoryos.usage.AiUsageLimitService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

@Schema(name = "AiUsageStanding", description = "The budget that binds the caller, and what they have spent against it")
public record AiUsageStandingResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) AiUsageLimitScope scope,
                                      @Nullable String groupName, @Nullable Long tokenBudget,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long tokensUsed,
                                      @Nullable BigDecimal costBudgetUsd,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal costUsed,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int periodDays,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant resetsAt) {
    public static AiUsageStandingResponse from(AiUsageLimitService.Standing value) {
        return new AiUsageStandingResponse(value.scope(), value.groupName(), value.tokenBudget(), value.tokensUsed(),
                value.costBudgetUsd(), value.costUsed(), value.periodDays(), value.resetsAt());
    }
}
