package io.memoryos.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.OperationDispatchPort;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/** MEM-244: a slow delivery holds one slot, not the reader. */
class RedisStreamWorkerSlotsTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);

    @Test
    @SuppressWarnings("unchecked")
    void aSlowDeliveryDoesNotKeepTheOtherSlotsIdle() throws Exception {
        var slow = new CountDownLatch(1);
        var others = new CountDownLatch(3);
        var first = new AtomicInteger();
        IngestionCoordinator coordinator = delivery -> {
            if (first.getAndIncrement() == 0) {
                try {
                    slow.await();
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            } else {
                others.countDown();
            }
            return IngestionCoordinator.Outcome.COMPLETED;
        };
        // Two slots; four deliveries wait in the stream. Redis hands out as many as the reader asks for.
        var waiting = new ArrayList<MapRecord<String, Object, Object>>();
        for (int n = 1; n <= 4; n++) waiting.add(record(n));
        var asked = new CopyOnWriteArrayList<Long>();
        when(redis.opsForStream().read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenAnswer(call -> {
                    StreamOffset<String> offset = call.getArgument(2);
                    StreamReadOptions options = call.getArgument(1);
                    if (!"ingestion".equals(offset.getKey())) {
                        Thread.sleep(50);
                        return List.of();
                    }
                    synchronized (waiting) {
                        if (waiting.isEmpty()) {
                            Thread.sleep(50);
                            return List.of();
                        }
                        asked.add(options.getCount());
                        int count = (int) Math.min(options.getCount(), waiting.size());
                        var handed = List.copyOf(waiting.subList(0, count));
                        waiting.subList(0, count).clear();
                        return handed;
                    }
                });
        var worker = worker(coordinator);
        worker.start();
        try {
            // The first delivery is still running: the reader used to wait for it before reading again.
            assertThat(others.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(asked.getFirst()).isEqualTo(2L);
            assertThat(asked.subList(1, asked.size())).containsOnly(1L);
        } finally {
            slow.countDown();
            worker.stop();
        }
    }

    private static MapRecord<String, Object, Object> record(int number) {
        return MapRecord.create("ingestion", Map.<Object, Object>of(
                "tenant_id", UUID.randomUUID().toString(), "operation_kind", "INGESTION",
                "operation_id", UUID.randomUUID().toString(), "delivery_id", UUID.randomUUID().toString()))
                .withId(RecordId.of(number + "-0"));
    }

    private RedisStreamWorker worker(IngestionCoordinator coordinator) {
        var ingestion = new RedisExecutionProperties.Workload("ingestion", "workers", 2, 0);
        var other = new RedisExecutionProperties.Workload("other", "others", 1, 0);
        var properties = new RedisExecutionProperties(Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(2),
                Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofHours(1), Duration.ofMinutes(2),
                Duration.ofSeconds(5), 1_000, ingestion, other, other, other, other, other);
        return new RedisStreamWorker(redis, mock(RedisExecutionTopology.class), properties,
                mock(OperationDispatchPort.class), coordinator, mock(RedisExecutionMetrics.class), OpenTelemetry.noop());
    }
}
