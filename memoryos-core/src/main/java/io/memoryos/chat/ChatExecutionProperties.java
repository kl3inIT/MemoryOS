package io.memoryos.chat;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token and cost budgets are optional caps: unset means none, not a large sentinel value. As Onyx, a turn's input is
 * bounded by the model's own window, not a deployment cap, and tool calls only by {@code maxCycles}
 * ({@code MAX_LLM_CYCLES}); {@code contextTokenLimit} and {@code mcpCallLimit} are optional overrides (Onyx
 * {@code GEN_AI_MAX_TOKENS}). {@code maxOutputTokens} is the room reserved for the answer when the input budget is
 * computed (Onyx {@code GEN_AI_NUM_RESERVED_OUTPUT_TOKENS}), never a cap on the answer.
 * A turn has no total deadline (as Onyx): the lease only detects a dead process and is renewed while the run lives.
 */
@ConfigurationProperties("memoryos.chat.execution")
public record ChatExecutionProperties(int concurrency, Duration leaseTtl, Duration leaseRenewal, Duration providerReadTimeout,
                                      int maxCycles, int maxOutputTokens, @Nullable Integer contextTokenLimit, int maxAnswerCharacters,
                                      @Nullable Integer tokenBudget, @Nullable Double costBudgetUsd, @Nullable Integer mcpCallLimit,
                                      Duration mcpCallTimeout) {
    public ChatExecutionProperties {
        if (mcpCallLimit != null && mcpCallLimit < 1 || invalid(mcpCallTimeout, Duration.ofMinutes(5)))
            throw new IllegalArgumentException("Invalid Chat MCP limits");
        if (concurrency < 1 || concurrency > 1000 || invalid(leaseTtl, Duration.ofDays(1)) || invalid(leaseRenewal, Duration.ofDays(1))
                || leaseRenewal.multipliedBy(2).compareTo(leaseTtl) > 0 || invalid(providerReadTimeout, Duration.ofMinutes(10))
                || maxCycles < 1 || maxCycles > 20
                || maxOutputTokens < 1 || contextTokenLimit != null && contextTokenLimit < 1024 || maxAnswerCharacters < 1
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

    public int contextCap() { return contextTokenLimit == null ? Integer.MAX_VALUE : contextTokenLimit; }

    public int mcpCallCap() { return mcpCallLimit == null ? Integer.MAX_VALUE : mcpCallLimit; }
}
