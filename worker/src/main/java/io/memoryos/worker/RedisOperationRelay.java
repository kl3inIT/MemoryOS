package io.memoryos.worker;

import io.memoryos.ingestion.DispatchClaim;
import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;

import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

final class RedisOperationRelay {

    private final StringRedisTemplate redis;
    private final OperationDispatchPort dispatch;
    private final RedisExecutionProperties properties;
    private final RedisExecutionMetrics metrics;

    RedisOperationRelay(
            StringRedisTemplate redis,
            OperationDispatchPort dispatch,
            RedisExecutionProperties properties,
            RedisExecutionMetrics metrics
    ) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    void relay(OperationWorkload workload) {
        RedisExecutionProperties.Workload settings = properties.workload(workload);
        Long size = redis.opsForStream().size(settings.stream());
        if (size != null && size >= properties.maxStreamDepth()) {
            metrics.dispatch(workload, RedisExecutionMetrics.DispatchOutcome.BACKPRESSURE);
            return;
        }
        int available = size == null
                ? settings.batchSize()
                : (int) Math.min(settings.batchSize(), properties.maxStreamDepth() - size);
        if (available < 1) {
            return;
        }
        for (DispatchClaim claim : dispatch.claim(workload, available)) {
            publish(settings, claim);
        }
    }

    private void publish(RedisExecutionProperties.Workload settings, DispatchClaim claim) {
        try {
            var messageId = Objects.requireNonNull(
                    redis.opsForStream().add(settings.stream(), Map.of(
                            "tenant_id", claim.delivery().tenantId().value().toString(),
                            "operation_kind", claim.delivery().workload().name(),
                            "operation_id", claim.delivery().operationId().value().toString(),
                            "delivery_id", claim.delivery().deliveryId().toString()
                    )),
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
        } catch (DataAccessException exception) {
            dispatch.defer(claim, "REDIS_TRANSPORT_UNAVAILABLE", properties.transportBackoff());
            metrics.dispatch(claim.delivery().workload(), RedisExecutionMetrics.DispatchOutcome.DEFERRED);
        }
    }
}
