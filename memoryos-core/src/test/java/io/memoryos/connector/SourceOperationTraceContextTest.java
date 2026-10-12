package io.memoryos.connector;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class SourceOperationTraceContextTest {
    @Test void ignoresMissingAndUntrustedContextWithoutBlockingWork() {
        String trace = "1234567890abcdef1234567890abcdef";
        String span = "1234567890abcdef";
        assertThat(SourceOperationTraceContext.from(trace, span)).isNotNull();
        assertThat(SourceOperationTraceContext.from(null, span)).isNull();
        assertThat(SourceOperationTraceContext.from(trace, null)).isNull();
        assertThat(SourceOperationTraceContext.from("0".repeat(32), span)).isNull();
        assertThat(SourceOperationTraceContext.from(trace, "0".repeat(16))).isNull();
        assertThat(SourceOperationTraceContext.from("x".repeat(4096), span)).isNull();
        assertThat(SourceOperationTraceContext.from(trace, "secret-bearing-input")).isNull();
    }
}
