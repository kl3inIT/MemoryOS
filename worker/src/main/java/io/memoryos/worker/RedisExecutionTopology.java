package io.memoryos.worker;

import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StringRedisTemplate;

final class RedisExecutionTopology {
    private static final Map<String, String> TOPOLOGY_MARKER = Map.of("_memoryos_topology", "1");

    private final StringRedisTemplate redis;
    private final RedisExecutionProperties properties;

    RedisExecutionTopology(StringRedisTemplate redis, RedisExecutionProperties properties) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    void reconcileTopology() {
        try {
            ensureTopology();
        } catch (RuntimeException exception) {
            throw new RedisTopologyUnavailableException();
        }
    }

    void ensureTopology() {
        ensureGroup(properties.ingestion());
        ensureGroup(properties.cleanup());
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

        private RedisTopologyUnavailableException() {
            super("Redis execution topology is unavailable");
        }
    }
}
