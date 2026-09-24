package io.memoryos.usage;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AiCostDay(LocalDate day, String key, BigDecimal cost, long calls, long inputTokens, long outputTokens,
                        long cacheReadTokens) {}
