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
        Duration shutdownGrace,
        long maxStreamDepth,
        Workload ingestion,
        Workload cleanup,
        Workload search,
        Workload sourceSync,
        Workload selectionValidation,
        Workload userFile
) {

    public RedisExecutionProperties {
        requirePositive(topologyInterval, "topologyInterval");
        requirePositive(relayInterval, "relayInterval");
        requirePositive(rediscoveryDelay, "rediscoveryDelay");
        requirePositive(transportBackoff, "transportBackoff");
        requirePositive(consumerBlock, "consumerBlock");
        requirePositive(reclaimInterval, "reclaimInterval");
        requirePositive(reclaimIdle, "reclaimIdle");
        // How long a stopping Worker lets work it started finish; below the container's stop grace period.
        if (shutdownGrace == null) shutdownGrace = Duration.ofSeconds(100);
        requirePositive(shutdownGrace, "shutdownGrace");
        if (maxStreamDepth < 1) {
            throw new IllegalArgumentException("maxStreamDepth must be positive");
        }
        Objects.requireNonNull(ingestion, "ingestion must not be null");
        Objects.requireNonNull(cleanup, "cleanup must not be null");
        Objects.requireNonNull(sourceSync, "sourceSync must not be null");
        Objects.requireNonNull(selectionValidation, "selectionValidation must not be null");
        Objects.requireNonNull(search, "search must not be null");
        if (userFile == null) userFile = new Workload("memoryos:execution:user-file:operations:v1", "memoryos:execution:user-file:workers:v1", 1, 0);
    }

    Workload workload(OperationWorkload workload) {
        return switch (Objects.requireNonNull(workload, "workload must not be null")) {
            case INGESTION -> ingestion;
            case CLEANUP -> cleanup;
            case SOURCE_SYNC -> sourceSync;
            case SELECTION_VALIDATION -> selectionValidation;
            case SEARCH -> search;
            case USER_FILE -> userFile;
        };
    }

    /**
     * @param batchSize the deliveries one Worker runs at once for the workload
     * @param publishAhead the unread deliveries the relay keeps in the stream, at least one batch. Keep it below
     *        (deliveries running at once across Workers) x (rediscovery delay) / (time one delivery takes), or a
     *        delivery waits unread past the rediscovery delay and is published again
     */
    public record Workload(String stream, String group, int batchSize, int publishAhead) {

        public Workload {
            requireText(stream, "stream");
            requireText(group, "group");
            if (batchSize < 1 || batchSize > 32) {
                throw new IllegalArgumentException("batchSize must be between 1 and 32");
            }
            if (publishAhead < 0) {
                throw new IllegalArgumentException("publishAhead must not be negative");
            }
            // Never below one batch, or a Worker's free slots could not all be filled.
            publishAhead = Math.max(publishAhead, batchSize);
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
