package io.memoryos.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import org.springframework.dao.DataAccessResourceFailureException;
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
                    1_000,
                    new RedisExecutionProperties.Workload("ingestion", "ingestion-workers", 8),
                    new RedisExecutionProperties.Workload("cleanup", "cleanup-workers", 8)
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
    void doesNotMaskProgrammingFailureAsTransportUnavailability() {
        var programmingFailure = new IllegalStateException("programming failure");
        when(redis.opsForStream()).thenThrow(programmingFailure);

        assertThatThrownBy(topology::reconcileTopology).isSameAs(programmingFailure);
    }
}
