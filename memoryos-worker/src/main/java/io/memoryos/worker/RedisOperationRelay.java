package io.memoryos.worker;

import io.memoryos.ingestion.DispatchClaim;
import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

final class RedisOperationRelay {
    /** The most rows one claim takes; the dispatch port clamps a claim to the same number. */
    private static final int CLAIM_LIMIT = 32;

    private final StringRedisTemplate redis;
    private final OperationDispatchPort dispatch;
    private final RedisExecutionProperties properties;
    private final RedisExecutionMetrics metrics;
    private final OpenTelemetry telemetry;

    RedisOperationRelay(
            StringRedisTemplate redis,
            OperationDispatchPort dispatch,
            RedisExecutionProperties properties,
            RedisExecutionMetrics metrics,
            OpenTelemetry telemetry
    ) {
        this.telemetry = telemetry;
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    /**
     * Publishes due work until the stream holds the workload's publish-ahead of unread deliveries, or no more is due.
     * One run used to publish one batch, so a burst left the Workers idle while it waited for the next runs (MEM-244).
     * The publish-ahead bounds how long a delivery waits unread: past the rediscovery delay it is published again.
     */
    void relay(OperationWorkload workload) {
        RedisExecutionProperties.Workload settings = properties.workload(workload);
        long deadline = System.nanoTime() + properties.relayInterval().toNanos();
        do {
            Long size = redis.opsForStream().size(settings.stream());
            long depth = size == null ? 0 : size;
            if (depth >= properties.maxStreamDepth()) {
                metrics.dispatch(workload, RedisExecutionMetrics.DispatchOutcome.BACKPRESSURE);
                return;
            }
            long room = Math.min(settings.publishAhead() - unread(settings, depth), properties.maxStreamDepth() - depth);
            if (room < 1) {
                return;
            }
            int available = (int) Math.min(room, CLAIM_LIMIT);
            List<DispatchClaim> claims = dispatch.claim(workload, available);
            for (DispatchClaim claim : claims) {
                if (!publish(settings, claim)) {
                    return;
                }
            }
            if (claims.size() < available) {
                return;
            }
        } while (System.nanoTime() < deadline);
    }

    /**
     * Deliveries no Worker has read yet. A delivered entry stays in the stream until it is acknowledged and deleted,
     * so the stream's length also counts work in progress, which must not hold back new work.
     */
    private long unread(RedisExecutionProperties.Workload settings, long depth) {
        var pending = redis.opsForStream().pending(settings.stream(), settings.group());
        long delivered = pending == null ? 0 : pending.getTotalPendingMessages();
        return Math.max(0, depth - delivered);
    }

    /** @return false when Redis or the record of the publication failed, and the claim was deferred */
    private boolean publish(RedisExecutionProperties.Workload settings, DispatchClaim claim) {
        var span = OperationTracing.start(telemetry, "memoryos.operation.publish", SpanKind.PRODUCER, claim.delivery());
        try (var _ = span.makeCurrent()) {
            Map<String, String> payload = new HashMap<>(Map.of(
                    "tenant_id", claim.delivery().tenantId().value().toString(),
                    "operation_kind", claim.delivery().workload().name(),
                    "operation_id", claim.delivery().operationId().value().toString(),
                    "delivery_id", claim.delivery().deliveryId().toString()));
            if (claim.delivery().origin() != null) {
                payload.put("origin_trace_id", claim.delivery().origin().traceId());
                payload.put("origin_span_id", claim.delivery().origin().spanId());
            }
            if (span.getSpanContext().isValid()) {
                payload.put("publish_trace_id", span.getSpanContext().getTraceId());
                payload.put("publish_span_id", span.getSpanContext().getSpanId());
            }
            var messageId = Objects.requireNonNull(
                    redis.opsForStream().add(settings.stream(), payload),
                    "Redis XADD message id must not be null"
            );
            boolean recorded = dispatch.recordPublished(
                    claim,
                    messageId.getValue(),
                    properties.rediscoveryDelay()
            );
            metrics.dispatch(
                    claim.delivery().workload(),
                    recorded
                            ? RedisExecutionMetrics.DispatchOutcome.PUBLISHED
                            : RedisExecutionMetrics.DispatchOutcome.STALE
            );
            return true;
        } catch (DataAccessException exception) {
            span.setStatus(StatusCode.ERROR);
            span.setAttribute("error.type", exception.getClass().getName());
            dispatch.defer(claim, "REDIS_TRANSPORT_UNAVAILABLE", properties.transportBackoff());
            metrics.dispatch(claim.delivery().workload(), RedisExecutionMetrics.DispatchOutcome.DEFERRED);
            return false;
        } finally {
            span.end();
        }
    }
}
