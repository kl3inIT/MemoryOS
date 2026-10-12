package io.memoryos.usage;

import java.math.BigDecimal;

public record AiCostTotals(BigDecimal cost, BigDecimal externalCost, long calls, long unknownCostCalls, long inputTokens,
                           long outputTokens, long cacheReadTokens, long imageCount, BigDecimal audioSeconds, long activePeople) {}
