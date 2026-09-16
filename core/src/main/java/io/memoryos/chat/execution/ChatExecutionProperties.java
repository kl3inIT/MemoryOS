package io.memoryos.chat.execution;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token and cost budgets are optional caps: unset means none, not a large sentinel value.
 * A turn has no total deadline (as Onyx): the lease only detects a dead process and is renewed while the run lives.
 */
@ConfigurationProperties("memoryos.chat.execution")
public record ChatExecutionProperties(int concurrency, Duration leaseTtl, Duration leaseRenewal, Duration providerReadTimeout,
                                      int maxCycles, int maxOutputTokens, int contextTokenLimit, int maxAnswerCharacters,
                                      @Nullable Integer tokenBudget, @Nullable Double costBudgetUsd) {
    public ChatExecutionProperties {
        if (concurrency < 1 || concurrency > 1000 || invalid(leaseTtl, Duration.ofDays(1)) || invalid(leaseRenewal, Duration.ofDays(1))
                || leaseRenewal.multipliedBy(2).compareTo(leaseTtl) > 0 || invalid(providerReadTimeout, Duration.ofMinutes(10))
                || maxCycles < 1 || maxCycles > 20
                || maxOutputTokens < 1 || contextTokenLimit < 1024 || maxAnswerCharacters < 1
                || maxAnswerCharacters > 1000000 || (tokenBudget != null && tokenBudget < 1)
                || (costBudgetUsd != null && (!Double.isFinite(costBudgetUsd) || costBudgetUsd <= 0)))
            throw new IllegalArgumentException("Invalid Chat execution limits");
    }

    private static boolean invalid(@Nullable Duration value, Duration max) {
        return value == null || value.toMillis() <= 0 || value.compareTo(max) > 0;
    }

    public boolean costCapped() { return costBudgetUsd != null; }

    /** Native Budget takes plain numbers, so an absent cap becomes the type maximum only at that boundary. */
    public int tokenCap() { return tokenBudget == null ? Integer.MAX_VALUE : tokenBudget; }

    public double costCap() { return costBudgetUsd == null ? Double.MAX_VALUE : costBudgetUsd; }
}
