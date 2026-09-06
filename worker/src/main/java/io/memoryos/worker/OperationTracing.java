package io.memoryos.worker;

import io.memoryos.ingestion.OperationDelivery;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

final class OperationTracing {
    private OperationTracing() {}

    static Span start(OpenTelemetry telemetry, String name, SpanKind kind, OperationDelivery delivery) {
        var builder = telemetry.getTracer("io.memoryos.worker")
                .spanBuilder(name).setNoParent().setSpanKind(kind)
                .setAttribute("operation_id", delivery.operationId().value().toString())
                .setAttribute("delivery_id", delivery.deliveryId().toString())
                .setAttribute("workload", delivery.workload().name());
        if (delivery.origin() != null) {
            var origin = delivery.origin();
            builder.addLink(SpanContext.createFromRemoteParent(origin.traceId(), origin.spanId(),
                    TraceFlags.getDefault(), TraceState.getDefault()));
        }
        return builder.startSpan();
    }
}
