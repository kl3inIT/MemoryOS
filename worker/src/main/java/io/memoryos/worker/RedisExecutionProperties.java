package io.memoryos.worker;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.redis")
public record RedisExecutionProperties(
        Duration topologyInterval,
        Workload ingestion,
        Workload cleanup
) {

    public RedisExecutionProperties {
        requirePositive(topologyInterval);
        Objects.requireNonNull(ingestion, "ingestion must not be null");
        Objects.requireNonNull(cleanup, "cleanup must not be null");
    }

    public record Workload(String stream, String group) {

        public Workload {
            requireText(stream, "stream");
            requireText(group, "group");
        }
    }

    private static void requirePositive(Duration value) {
        Objects.requireNonNull(value, "topologyInterval must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("topologyInterval must be positive");
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
