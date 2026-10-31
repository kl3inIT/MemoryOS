package io.memoryos.worker;

import io.memoryos.ingestion.OperationWorkload;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
final class RedisExecutionMetrics {

    enum DispatchOutcome {
        BACKPRESSURE,
        PUBLISHED,
        STALE,
        DEFERRED
    }

    enum DeliveryOutcome {
        RECLAIMED,
        INVALID,
        ACKED,
        ACK_MISSED,
        PENDING
    }

    private final StringRedisTemplate redis;
    private final RedisExecutionProperties properties;
    private final Map<OperationWorkload, Map<DispatchOutcome, Counter>> dispatch =
            new EnumMap<>(OperationWorkload.class);
    private final Map<OperationWorkload, Map<DeliveryOutcome, Counter>> delivery =
            new EnumMap<>(OperationWorkload.class);
    private final Map<OperationWorkload, Timer> processing = new EnumMap<>(OperationWorkload.class);

    RedisExecutionMetrics(
            MeterRegistry registry,
            StringRedisTemplate redis,
            RedisExecutionProperties properties
    ) {
        Objects.requireNonNull(registry, "registry must not be null");
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        for (OperationWorkload workload : OperationWorkload.values()) {
            String workloadTag = workload.name().toLowerCase(Locale.ROOT);
            var dispatchCounters = new EnumMap<DispatchOutcome, Counter>(DispatchOutcome.class);
            for (DispatchOutcome outcome : DispatchOutcome.values()) {
                dispatchCounters.put(outcome, Counter.builder("memoryos.redis.dispatch")
                        .tag("workload", workloadTag)
                        .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                        .register(registry));
            }
            dispatch.put(workload, dispatchCounters);

            var deliveryCounters = new EnumMap<DeliveryOutcome, Counter>(DeliveryOutcome.class);
            for (DeliveryOutcome outcome : DeliveryOutcome.values()) {
                deliveryCounters.put(outcome, Counter.builder("memoryos.redis.delivery")
                        .tag("workload", workloadTag)
                        .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                        .register(registry));
            }
            delivery.put(workload, deliveryCounters);

            processing.put(workload, Timer.builder("memoryos.redis.operation.processing")
                    .tag("workload", workloadTag)
                    .register(registry));
            Gauge.builder("memoryos.redis.stream.depth", this, metrics -> metrics.streamDepth(workload))
                    .tag("workload", workloadTag)
                    .register(registry);
            Gauge.builder("memoryos.redis.stream.pending", this, metrics -> metrics.pending(workload))
                    .tag("workload", workloadTag)
                    .register(registry);
        }
    }

    void dispatch(OperationWorkload workload, DispatchOutcome outcome) {
        dispatch.get(workload).get(outcome).increment();
    }

    void delivery(OperationWorkload workload, DeliveryOutcome outcome) {
        delivery.get(workload).get(outcome).increment();
    }

    long startProcessing() {
        return System.nanoTime();
    }

    void completeProcessing(OperationWorkload workload, long startedAt) {
        processing.get(workload).record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }


    private double streamDepth(OperationWorkload workload) {
        try {
            Long value = redis.opsForStream().size(properties.workload(workload).stream());
            return value == null ? Double.NaN : value.doubleValue();
        } catch (DataAccessException exception) {
            return Double.NaN;
        }
    }

    private double pending(OperationWorkload workload) {
        try {
            var settings = properties.workload(workload);
            return redis.opsForStream().pending(settings.stream(), settings.group()).getTotalPendingMessages();
        } catch (DataAccessException exception) {
            return Double.NaN;
        }
    }
}
