package io.memoryos.usage;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

public record AiCostRow(String key, String label, @Nullable String detail, long calls, long unknownCostCalls, long inputTokens,
                        long outputTokens, BigDecimal cost) {}
