package io.memoryos.worker;

import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.tenant.TenantId;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "memoryos.worker.enabled", havingValue = "true", matchIfMissing = true)
final class RedisStreamWorker implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisStreamWorker.class);

    private final StringRedisTemplate redis;
    private final RedisExecutionTopology topology;
    private final RedisExecutionProperties properties;
    private final OperationDispatchPort dispatch;
    private final IngestionCoordinator coordinator;
    private final RedisExecutionMetrics metrics;
    private final OpenTelemetry telemetry;
    private final String consumerId = "memoryos-worker-" + UUID.randomUUID();

    private volatile boolean running;
    private ExecutorService consumers;

    RedisStreamWorker(
            StringRedisTemplate redis,
            RedisExecutionTopology topology,
            RedisExecutionProperties properties,
            OperationDispatchPort dispatch,
            IngestionCoordinator coordinator,
            RedisExecutionMetrics metrics,
            OpenTelemetry telemetry
    ) {
        this.telemetry = telemetry;
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.topology = Objects.requireNonNull(topology, "topology must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch must not be null");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        topology.reconcileTopology();
        running = true;
        ExecutorService startedConsumers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("memoryos-redis-consumer-", 0).factory()
        );
        consumers = startedConsumers;
        startedConsumers.submit(() -> consume(OperationWorkload.INGESTION, startedConsumers));
        startedConsumers.submit(() -> consume(OperationWorkload.CLEANUP, startedConsumers));
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (consumers == null) {
            return;
        }
        consumers.shutdownNow();
        try {
            if (!consumers.awaitTermination(
                    properties.consumerBlock().plusSeconds(5).toMillis(),
                    TimeUnit.MILLISECONDS
            )) {
                LOGGER.atWarn().addKeyValue("event", "redis.consumer.shutdown_timeout")
                        .log("Redis consumers exceeded the shutdown window");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            consumers = null;
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @SuppressWarnings("unchecked")
    private void consume(OperationWorkload workload, ExecutorService executor) {
        RedisExecutionProperties.Workload settings = properties.workload(workload);
        long nextReclaim = System.nanoTime();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (System.nanoTime() >= nextReclaim) {
                    reclaim(settings, workload, executor);
                    nextReclaim = System.nanoTime() + properties.reclaimInterval().toNanos();
                }
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(settings.group(), consumerId),
                        StreamReadOptions.empty()
                                .count(settings.batchSize())
                                .block(properties.consumerBlock()),
                        StreamOffset.create(settings.stream(), ReadOffset.lastConsumed())
                );
                if (records != null && !records.isEmpty()) {
                    processBatch(settings, workload, records, executor);
                }
            } catch (DataAccessException exception) {
                Throwable cause = exception.getMostSpecificCause();
                LOGGER.atWarn().addKeyValue("event", "redis.transport.failed")
                        .addKeyValue("workload", workload.name())
                        .addKeyValue("error_type", exception.getClass().getName())
                        .addKeyValue("cause_type", cause.getClass().getName())
                        .log("Redis operation failed; retry after transport backoff");
                pause(properties.transportBackoff());
            } catch (RuntimeException exception) {
                LOGGER.atError().addKeyValue("event", "redis.consumer.failed")
                        .addKeyValue("workload", workload.name())
                        .addKeyValue("error_type", exception.getClass().getName())
                        .log("Redis consumer failed; retry after backoff");
                pause(properties.transportBackoff());
            }
        }
    }

    private void reclaim(
            RedisExecutionProperties.Workload settings,
            OperationWorkload workload,
            ExecutorService executor
    ) {
        var operations = redis.opsForStream();
        var pending = operations.pending(
                settings.stream(),
                settings.group(),
                Range.unbounded(),
                settings.batchSize()
        );
        for (var message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(properties.reclaimIdle()) < 0) {
                continue;
            }
            List<MapRecord<String, Object, Object>> stored = operations.range(
                    settings.stream(),
                    Range.closed(message.getIdAsString(), message.getIdAsString())
            );
            if (stored.isEmpty()) {
                acknowledge(settings, message.getId());
                continue;
            }
            OperationDelivery delivery;
            try {
                delivery = delivery(stored.getFirst().getValue(), workload);
            } catch (IllegalArgumentException exception) {
                acknowledgeAndDelete(settings, message.getId());
                continue;
            }
            if (!dispatch.reclaimable(delivery)) {
                continue;
            }
            List<MapRecord<String, Object, Object>> claimed = operations.claim(
                    settings.stream(),
                    settings.group(),
                    consumerId,
                    properties.reclaimIdle(),
                    message.getId()
            );
            processBatch(settings, workload, claimed, executor);
            if (!claimed.isEmpty()) {
                metrics.delivery(workload, RedisExecutionMetrics.DeliveryOutcome.RECLAIMED);
            }
        }
    }

    private void processBatch(
            RedisExecutionProperties.Workload settings,
            OperationWorkload workload,
            List<MapRecord<String, Object, Object>> records,
            ExecutorService executor
    ) {
        var tasks = new ArrayList<Future<?>>(records.size());
        for (var record : records) {
            tasks.add(executor.submit(() -> process(settings, workload, record)));
        }
        for (Future<?> task : tasks) {
            try {
                task.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException exception) {
                throw new IllegalStateException("Redis delivery task failed", exception.getCause());
            }
        }
    }

    private void process(
            RedisExecutionProperties.Workload settings,
            OperationWorkload workload,
            MapRecord<String, Object, Object> record
    ) {
        OperationDelivery delivery;
        try {
            delivery = delivery(record.getValue(), workload);
        } catch (IllegalArgumentException exception) {
            LOGGER.atWarn().addKeyValue("event", "redis.delivery.invalid")
                    .addKeyValue("redis_message_id", record.getId().getValue())
                    .log("Discarded invalid internal Redis delivery");
            acknowledgeAndDelete(settings, record.getId());
            metrics.delivery(workload, RedisExecutionMetrics.DeliveryOutcome.INVALID);
            return;
        }
        long startedAt = metrics.startProcessing();
        var span = OperationTracing.start(telemetry, "memoryos.operation.process", SpanKind.CONSUMER, delivery);
        var published = SourceOperationTraceContext.from(
                optional(record.getValue(), "publish_trace_id"), optional(record.getValue(), "publish_span_id"));
        if (published != null) {
            span.addLink(io.opentelemetry.api.trace.SpanContext.createFromRemoteParent(
                    published.traceId(), published.spanId(), io.opentelemetry.api.trace.TraceFlags.getDefault(),
                    io.opentelemetry.api.trace.TraceState.getDefault()));
        }
        try (var scope = span.makeCurrent();
             var traceMdc = MDC.putCloseable("traceId", span.getSpanContext().getTraceId());
             var spanMdc = MDC.putCloseable("spanId", span.getSpanContext().getSpanId());
             var operationMdc = MDC.putCloseable("operation_id", delivery.operationId().value().toString());
             var deliveryMdc = MDC.putCloseable("delivery_id", delivery.deliveryId().toString());
             var workloadMdc = MDC.putCloseable("workload", workload.name())) {
            try {
                var outcome = coordinator.process(delivery);
                span.setAttribute("processing.outcome", outcome.name());
                if (outcome == IngestionCoordinator.Outcome.FAILED) {
                    span.setStatus(StatusCode.ERROR);
                }
                metrics.delivery(
                        workload,
                        acknowledgeAndDelete(settings, record.getId())
                                ? RedisExecutionMetrics.DeliveryOutcome.ACKED
                                : RedisExecutionMetrics.DeliveryOutcome.ACK_MISSED
                );
            } catch (RuntimeException exception) {
                span.setStatus(StatusCode.ERROR);
                span.setAttribute("error.type", exception.getClass().getName());
                metrics.delivery(workload, RedisExecutionMetrics.DeliveryOutcome.PENDING);
                LOGGER.atError().addKeyValue("event", "redis.delivery.pending")
                        .addKeyValue("operation_id", delivery.operationId().value())
                        .addKeyValue("delivery_id", delivery.deliveryId())
                        .addKeyValue("error_type", exception.getClass().getName())
                        .log("Delivery remains pending after processing failure");
            }
        } finally {
            span.end();
            metrics.completeProcessing(workload, startedAt);
        }
    }

    private static OperationDelivery delivery(Map<Object, Object> values, OperationWorkload expectedWorkload) {
        OperationWorkload workload = OperationWorkload.valueOf(value(values, "operation_kind"));
        if (workload != expectedWorkload) {
            throw new IllegalArgumentException("delivery workload does not match its stream");
        }
        return new OperationDelivery(
                new TenantId(UUID.fromString(value(values, "tenant_id"))),
                workload,
                new SourceOperationId(UUID.fromString(value(values, "operation_id"))),
                UUID.fromString(value(values, "delivery_id")),
                SourceOperationTraceContext.from(optional(values, "origin_trace_id"), optional(values, "origin_span_id"))
        );
    }

    private static String optional(Map<Object, Object> values, String key) {
        return values.get(key) instanceof String text ? text : null;
    }

    private static String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("missing delivery field " + key);
        }
        return text;
    }

    private boolean acknowledgeAndDelete(RedisExecutionProperties.Workload settings, RecordId recordId) {
        if (!acknowledge(settings, recordId)) {
            return false;
        }
        redis.opsForStream().delete(settings.stream(), recordId);
        return true;
    }

    private boolean acknowledge(RedisExecutionProperties.Workload settings, RecordId recordId) {
        Long acknowledged = redis.opsForStream().acknowledge(settings.stream(), settings.group(), recordId);
        return acknowledged != null && acknowledged == 1;
    }

    private void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
