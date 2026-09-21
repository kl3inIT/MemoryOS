package io.memoryos.usage;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A configured cap on AI spending. A limit sets a token budget, an estimated cost budget, or both; a call whose model
 * carries no price adds tokens but no cost, so only a token budget binds an unpriced model.
 */
public record AiUsageLimit(UUID id, AiUsageLimitScope scope, @Nullable UUID groupId, @Nullable String groupName,
                           @Nullable Long tokenBudget, @Nullable BigDecimal costBudgetUsd, int periodDays,
                           boolean enabled) {

    public AiUsageLimit {
        if (scope == null) throw AiCostException.invalid("Choose who the limit applies to.");
        if ((scope == AiUsageLimitScope.GROUP) != (groupId != null))
            throw AiCostException.invalid("Only a Group limit names a Group.");
        if (tokenBudget == null && costBudgetUsd == null)
            throw AiCostException.invalid("Set a token budget, a cost budget, or both.");
        if (tokenBudget != null && tokenBudget < 1) throw AiCostException.invalid("A token budget must be positive.");
        if (costBudgetUsd != null && costBudgetUsd.signum() <= 0)
            throw AiCostException.invalid("A cost budget must be positive.");
        if (periodDays < 1 || periodDays > AiCostService.MAX_DAYS)
            throw AiCostException.invalid("Choose a period of 1 to 366 days.");
    }
}
