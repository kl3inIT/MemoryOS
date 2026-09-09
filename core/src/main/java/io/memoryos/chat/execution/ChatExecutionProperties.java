package io.memoryos.chat.execution;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.chat.execution")
public record ChatExecutionProperties(int concurrency, Duration deadline, int maxCycles, int maxOutputTokens,
                                      int contextTokenLimit, int maxAnswerCharacters, int tokenBudget,
                                      double costBudgetUsd) {
    public ChatExecutionProperties {
        if (concurrency < 1 || concurrency > 1000 || deadline == null || deadline.isNegative() || deadline.isZero()
                || deadline.compareTo(Duration.ofMinutes(30)) > 0 || maxCycles < 1 || maxCycles > 20
                || maxOutputTokens < 1 || contextTokenLimit < 1024 || maxAnswerCharacters < 1
                || maxAnswerCharacters > 1000000 || tokenBudget < 1 || !Double.isFinite(costBudgetUsd) || costBudgetUsd <= 0)
            throw new IllegalArgumentException("Invalid Chat execution limits");
    }
}
