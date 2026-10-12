package io.memoryos.worker;

import io.memoryos.ingestion.OperationWorkload;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;

final class RedisExecutionTopology {
    private static final Map<String, String> TOPOLOGY_MARKER = Map.of("_memoryos_topology", "1");
    /** A reader asks Redis every few seconds while it has a free slot, and holds pending entries while it has none. */
    private static final Duration CONSUMER_GONE_AFTER = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final RedisExecutionProperties properties;

    RedisExecutionTopology(StringRedisTemplate redis, RedisExecutionProperties properties) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    void reconcileTopology() {
        try {
            ensureTopology();
        } catch (DataAccessException exception) {
            throw new RedisTopologyUnavailableException(exception);
        }
    }

    void ensureTopology() {
        for (var workload : OperationWorkload.values()) {
            ensureGroup(properties.workload(workload));
            removeGoneConsumers(properties.workload(workload));
        }
    }

    /**
     * Every Worker process reads under a new consumer name, so each restart left one behind for good (MEM-244). A
     * consumer with nothing pending that has not asked for an hour belongs to a process that is gone; one with
     * pending entries is left for the reclaim, which Redis could no longer do once the consumer is deleted.
     */
    private void removeGoneConsumers(RedisExecutionProperties.Workload workload) {
        var operations = redis.opsForStream();
        var consumers = operations.consumers(workload.stream(), workload.group());
        if (consumers == null) {
            return;
        }
        for (var consumer : consumers) {
            if (consumer.pendingCount() == 0 && consumer.idleTime().compareTo(CONSUMER_GONE_AFTER) > 0) {
                operations.deleteConsumer(workload.stream(), Consumer.from(workload.group(), consumer.consumerName()));
            }
        }
    }

    private void ensureGroup(RedisExecutionProperties.Workload workload) {
        if (groupExists(workload)) {
            return;
        }

        var operations = redis.opsForStream();
        var markerId = Objects.requireNonNull(
                operations.add(workload.stream(), TOPOLOGY_MARKER),
                "topology marker id must not be null"
        );
        try {
            try {
                operations.createGroup(workload.stream(), ReadOffset.from("0-0"), workload.group());
            } catch (DataAccessException exception) {
                if (!groupExists(workload)) {
                    throw exception;
                }
            }
        } finally {
            operations.delete(workload.stream(), markerId);
        }
    }

    private boolean groupExists(RedisExecutionProperties.Workload workload) {
        try {
            return redis.opsForStream()
                    .groups(workload.stream())
                    .stream()
                    .anyMatch(group -> workload.group().equals(group.groupName()));
        } catch (DataAccessException exception) {
            return false;
        }
    }

    private static final class RedisTopologyUnavailableException extends RuntimeException {

        private RedisTopologyUnavailableException(DataAccessException cause) {
            super("Redis execution topology is unavailable", cause);
        }
    }
}
