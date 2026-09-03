package io.memoryos.worker;

import io.memoryos.ingestion.OperationWorkload;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.redis")
public record RedisExecutionProperties(
        Duration topologyInterval,
        Duration relayInterval,
        Duration rediscoveryDelay,
        Duration transportBackoff,
        Duration consumerBlock,
        Duration reclaimInterval,
        Duration reclaimIdle,
        long maxStreamDepth,
        Workload ingestion,
        Workload cleanup
) {

    public RedisExecutionProperties {
        requirePositive(topologyInterval, "topologyInterval");
        requirePositive(relayInterval, "relayInterval");
        requirePositive(rediscoveryDelay, "rediscoveryDelay");
        requirePositive(transportBackoff, "transportBackoff");
        requirePositive(consumerBlock, "consumerBlock");
        requirePositive(reclaimInterval, "reclaimInterval");
        requirePositive(reclaimIdle, "reclaimIdle");
        if (maxStreamDepth < 1) {
            throw new IllegalArgumentException("maxStreamDepth must be positive");
        }
        Objects.requireNonNull(ingestion, "ingestion must not be null");
        Objects.requireNonNull(cleanup, "cleanup must not be null");
    }

    Workload workload(OperationWorkload workload) {
        return switch (Objects.requireNonNull(workload, "workload must not be null")) {
            case INGESTION -> ingestion;
            case CLEANUP -> cleanup;
        };
    }

    public record Workload(String stream, String group, int batchSize) {

        public Workload {
            requireText(stream, "stream");
            requireText(group, "group");
            if (batchSize < 1 || batchSize > 32) {
                throw new IllegalArgumentException("batchSize must be between 1 and 32");
            }
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
