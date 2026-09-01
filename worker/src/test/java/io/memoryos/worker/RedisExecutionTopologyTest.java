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
                    new RedisExecutionProperties.Workload("ingestion", "ingestion-workers"),
                    new RedisExecutionProperties.Workload("cleanup", "cleanup-workers")
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
