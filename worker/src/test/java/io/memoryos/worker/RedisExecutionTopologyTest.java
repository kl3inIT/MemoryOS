package io.memoryos.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisExecutionTopologyTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisExecutionTopology topology = new RedisExecutionTopology(
            redis,
            new RedisExecutionProperties(
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(1),
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(10),
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(100),
                    1_000,
                    new RedisExecutionProperties.Workload("ingestion", "ingestion-workers", 8, 0),
                    new RedisExecutionProperties.Workload("cleanup", "cleanup-workers", 8, 0),
                    new RedisExecutionProperties.Workload("search", "search-workers", 2, 0),
                    new RedisExecutionProperties.Workload("sync", "sync-workers", 2, 0),
                    new RedisExecutionProperties.Workload("selection", "selection-workers", 2, 0),
                    new RedisExecutionProperties.Workload("files", "file-workers", 1, 0)
            )
    );

    @Test
    void translatesRedisAccessFailureWithoutExposingItsDiagnostic() {
        when(redis.opsForStream()).thenThrow(
                new DataAccessResourceFailureException("redis-credential-bearing-diagnostic")
        );

        assertThatThrownBy(topology::reconcileTopology)
                .hasMessage("Redis execution topology is unavailable")
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageNotContaining("redis-credential-bearing-diagnostic");
    }

    @Test
    @SuppressWarnings("unchecked")
    void forgetsConsumersOfWorkersThatAreGoneAndKeepsTheRest() {
        // MEM-244: every Worker process reads under a new name; staging held more than 230 after its restarts.
        StreamOperations<String, Object, Object> streams = mock(StreamOperations.class);
        when(redis.opsForStream()).thenReturn(streams);
        when(streams.groups(anyString())).thenAnswer(call -> StreamInfo.XInfoGroups.fromList(List.of(
                List.<Object>of("name", groupOf(call.getArgument(0)), "consumers", 3L, "pending", 1L,
                        "last-delivered-id", "1-0"))));
        long twoHours = Duration.ofHours(2).toMillis();
        when(streams.consumers("search", "search-workers")).thenReturn(StreamInfo.XInfoConsumers.fromList(
                "search-workers", List.of(
                        List.<Object>of("name", "gone", "pending", 0L, "idle", twoHours),
                        List.<Object>of("name", "gone-with-work", "pending", 1L, "idle", twoHours),
                        List.<Object>of("name", "reading", "pending", 0L, "idle", 1_500L))));

        topology.reconcileTopology();

        verify(streams).deleteConsumer("search", Consumer.from("search-workers", "gone"));
        verify(streams, never()).deleteConsumer(eq("search"), eq(Consumer.from("search-workers", "gone-with-work")));
        verify(streams, never()).deleteConsumer(eq("search"), eq(Consumer.from("search-workers", "reading")));
        verify(streams, never()).add(anyString(), any(Map.class));
    }

    private static String groupOf(String stream) {
        return switch (stream) {
            case "files" -> "file-workers";
            case "sync" -> "sync-workers";
            case "selection" -> "selection-workers";
            default -> stream + "-workers";
        };
    }

    @Test
    void doesNotMaskProgrammingFailureAsTransportUnavailability() {
        var programmingFailure = new IllegalStateException("programming failure");
        when(redis.opsForStream()).thenThrow(programmingFailure);

        assertThatThrownBy(topology::reconcileTopology).isSameAs(programmingFailure);
    }
}
