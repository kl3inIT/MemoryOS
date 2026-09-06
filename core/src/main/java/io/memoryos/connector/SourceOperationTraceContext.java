package io.memoryos.connector;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/** Optional causal origin. Never used for authorization, eligibility or sampling. */
public record SourceOperationTraceContext(String traceId, String spanId) {
    public static @Nullable SourceOperationTraceContext from(@Nullable String traceId, @Nullable String spanId) {
        if (traceId == null || spanId == null
                || !traceId.matches("[0-9a-f]{32}") || !spanId.matches("[0-9a-f]{16}")
                || traceId.equals("0".repeat(32)) || spanId.equals("0".repeat(16))) {
            return null;
        }
        return new SourceOperationTraceContext(traceId, spanId);
    }

    public static @Nullable SourceOperationTraceContext current() {
        return from(MDC.get("traceId"), MDC.get("spanId"));
    }
}
