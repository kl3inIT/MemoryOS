package io.memoryos.worker;

import static org.assertj.core.api.Assertions.assertThat;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.iam.TenantId;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.ReadableSpan;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationTracingTest {
    @Test void retrySpansAreIndependentRootsWithTheSameCausalLink() {
        try (var provider = SdkTracerProvider.builder().build()) {
            var telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            var origin = Objects.requireNonNull(SourceOperationTraceContext.from(
                    "1234567890abcdef1234567890abcdef", "1234567890abcdef"));
            var delivery = new OperationDelivery(new TenantId(UUID.randomUUID()), OperationWorkload.INGESTION,
                    new SourceOperationId(UUID.randomUUID()), UUID.randomUUID(), origin);
            var unrelated = telemetry.getTracer("test").spanBuilder("scheduler").startSpan();
            try (var _ = unrelated.makeCurrent()) {
                var first = OperationTracing.start(telemetry, "process", SpanKind.CONSUMER, delivery);
                var retry = OperationTracing.start(telemetry, "process", SpanKind.CONSUMER, delivery);
                first.end(); retry.end();
                var data = ((ReadableSpan) first).toSpanData();
                assertThat(data.getParentSpanContext().isValid()).isFalse();
                assertThat(data.getLinks()).hasSize(1);
                assertThat(data.getLinks().getFirst().getSpanContext().getTraceId()).isEqualTo(origin.traceId());
                assertThat(retry.getSpanContext().getTraceId()).isNotEqualTo(first.getSpanContext().getTraceId());
                assertThat(((ReadableSpan) retry).toSpanData().getLinks()).isEqualTo(data.getLinks());
            } finally { unrelated.end(); }
        }
    }
}
