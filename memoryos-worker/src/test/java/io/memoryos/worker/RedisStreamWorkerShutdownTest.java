package io.memoryos.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/** MEM-244: a stopping Worker lets started work finish, and leaves pending what outlasts its grace. */
class RedisStreamWorkerShutdownTest {
    private final RecordId id = RecordId.of("1-0");
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private final CountDownLatch started = new CountDownLatch(1);

    @Test
    void workStartedBeforeTheStopFinishesAndIsAcknowledged() throws Exception {
        var interrupted = new AtomicBoolean();
        IngestionCoordinator coordinator = delivery -> {
            started.countDown();
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException cut) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new CancellationException();
            }
            return IngestionCoordinator.Outcome.COMPLETED;
        };
        var worker = worker(coordinator, Duration.ofSeconds(10));
        worker.start();
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        worker.stop();

        assertThat(interrupted).isFalse();
        verify(redis.opsForStream()).acknowledge("ingestion", "workers", id);
        verify(redis.opsForStream()).delete("ingestion", id);
    }

    @Test
    void workThatOutlastsTheGraceIsInterruptedAndStaysPending() throws Exception {
        IngestionCoordinator coordinator = delivery -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException cut) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            }
            return IngestionCoordinator.Outcome.COMPLETED;
        };
        var worker = worker(coordinator, Duration.ofMillis(500));
        worker.start();
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        long before = System.nanoTime();
        worker.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(15));
        verify(redis.opsForStream(), never()).acknowledge(any(String.class), any(String.class), any(RecordId[].class));
        verify(redis.opsForStream(), never()).delete(any(String.class), any(RecordId[].class));
    }

    /** One ingestion delivery the first time the ingestion stream is read; nothing on any other read. */
    @SuppressWarnings("unchecked")
    private RedisStreamWorker worker(IngestionCoordinator coordinator, Duration grace) {
        var ingestion = new RedisExecutionProperties.Workload("ingestion", "workers", 8, 0);
        var other = new RedisExecutionProperties.Workload("other", "others", 1, 0);
        var properties = new RedisExecutionProperties(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(2),
                Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofHours(1), Duration.ofMinutes(2), grace, 1_000,
                ingestion, other, other, other, other, other);
        MapRecord<String, Object, Object> record = MapRecord.create("ingestion", Map.<Object, Object>of(
                "tenant_id", UUID.randomUUID().toString(), "operation_kind", "INGESTION",
                "operation_id", UUID.randomUUID().toString(), "delivery_id", UUID.randomUUID().toString())).withId(id);
        var delivered = new AtomicBoolean();
        when(redis.opsForStream().read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenAnswer(call -> {
                    StreamOffset<String> offset = call.getArgument(2);
                    if ("ingestion".equals(offset.getKey()) && delivered.compareAndSet(false, true)) return List.of(record);
                    Thread.sleep(100);
                    return List.of();
                });
        when(redis.opsForStream().acknowledge("ingestion", "workers", id)).thenReturn(1L);
        return new RedisStreamWorker(redis, mock(RedisExecutionTopology.class), properties,
                mock(OperationDispatchPort.class), coordinator, mock(RedisExecutionMetrics.class), OpenTelemetry.noop());
    }
}
