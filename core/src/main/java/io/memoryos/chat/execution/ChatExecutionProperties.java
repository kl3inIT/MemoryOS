package io.memoryos.chat.execution;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Token and cost budgets are optional caps: unset means none, not a large sentinel value. */
@ConfigurationProperties("memoryos.chat.execution")
public record ChatExecutionProperties(int concurrency, Duration deadline, int maxCycles, int maxOutputTokens,
                                      int contextTokenLimit, int maxAnswerCharacters, @Nullable Integer tokenBudget,
                                      @Nullable Double costBudgetUsd, int mcpCallLimit, Duration mcpCallTimeout) {
    /** Defaults keep existing constructions working; MCP limits are configuration like every other bound. */
    public ChatExecutionProperties(int concurrency, Duration deadline, int maxCycles, int maxOutputTokens,
                                   int contextTokenLimit, int maxAnswerCharacters, @Nullable Integer tokenBudget,
                                   @Nullable Double costBudgetUsd) {
        this(concurrency, deadline, maxCycles, maxOutputTokens, contextTokenLimit, maxAnswerCharacters, tokenBudget,
                costBudgetUsd, 10, Duration.ofSeconds(60));
    }

    public ChatExecutionProperties {
        if (mcpCallLimit < 1 || mcpCallLimit > 100 || mcpCallTimeout == null || mcpCallTimeout.isNegative()
                || mcpCallTimeout.isZero() || mcpCallTimeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Invalid Chat MCP limits");
        if (concurrency < 1 || concurrency > 1000 || deadline == null || deadline.isNegative() || deadline.isZero()
                || deadline.compareTo(Duration.ofMinutes(30)) > 0 || maxCycles < 1 || maxCycles > 20
                || maxOutputTokens < 1 || contextTokenLimit < 1024 || maxAnswerCharacters < 1
                || maxAnswerCharacters > 1000000 || (tokenBudget != null && tokenBudget < 1)
                || (costBudgetUsd != null && (!Double.isFinite(costBudgetUsd) || costBudgetUsd <= 0)))
            throw new IllegalArgumentException("Invalid Chat execution limits");
    }

    public boolean costCapped() { return costBudgetUsd != null; }

    /** Native Budget takes plain numbers, so an absent cap becomes the type maximum only at that boundary. */
    public int tokenCap() { return tokenBudget == null ? Integer.MAX_VALUE : tokenBudget; }

    public double costCap() { return costBudgetUsd == null ? Double.MAX_VALUE : costBudgetUsd; }
}
