package io.memoryos.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "memoryos.worker.enabled=false",
                "db-scheduler.enabled=false",
                "management.endpoint.health.group.readiness.include=readinessState,db,redis",
                "memoryos.redis.ingestion.stream=memoryos:test:work:ingestion",
                "memoryos.redis.ingestion.group=memoryos-test-ingestion-workers",
                "memoryos.redis.cleanup.stream=memoryos:test:work:cleanup",
                "memoryos.redis.cleanup.group=memoryos-test-cleanup-workers",
                "spring.data.redis.repositories.enabled=false"
        }
)
@AutoConfigureTestRestTemplate
@Testcontainers(disabledWithoutDocker = true)
class RedisExecutionTopologyIntegrationTest {

    @org.springframework.test.context.DynamicPropertySource
    static void databaseProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        WorkerPostgresDatabase.configure(registry);
    }

    @Autowired
    private RedisExecutionTopology topology;

    @Autowired
    private RedisExecutionProperties properties;

    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private TestRestTemplate http;

    @SuppressWarnings("unchecked")
    @Test
    void idleStreamReadReturnsNormallyWithProductionBlockDuration() {
        topology.ensureTopology();
        var records = redis.opsForStream().read(
                Consumer.from(properties.cleanup().group(), "idle-regression"),
                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                StreamOffset.create(properties.cleanup().stream(), ReadOffset.lastConsumed())
        );
        assertTrue(records == null || records.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void createsGroupsAndAcknowledgesIdentifierOnlyDelivery() {
        topology.ensureTopology();
        topology.ensureTopology();

        assertGroupExists(properties.ingestion());
        assertGroupExists(properties.cleanup());
        assertEquals(
                HttpStatus.OK,
                http.getForEntity("/actuator/health/readiness", String.class).getStatusCode()
        );
        var operations = redis.opsForStream();
        var recordId = operations.add(properties.ingestion().stream(), Map.of(
                "tenant_id", UUID.randomUUID().toString(),
                "operation_kind", "INGESTION",
                "operation_id", UUID.randomUUID().toString(),
                "delivery_id", UUID.randomUUID().toString()
        ));
        assertNotNull(recordId);

        var records = operations.read(
                Consumer.from(properties.ingestion().group(), "mem42-integration"),
                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                StreamOffset.create(properties.ingestion().stream(), ReadOffset.lastConsumed())
        );

        assertNotNull(records);
        assertEquals(1, records.size());
        assertEquals(1L, operations.pending(
                properties.ingestion().stream(),
                properties.ingestion().group()
        ).getTotalPendingMessages());
        assertEquals(1L, operations.acknowledge(
                properties.ingestion().stream(),
                properties.ingestion().group(),
                records.getFirst().getId()
        ));
        assertEquals(0L, operations.pending(
                properties.ingestion().stream(),
                properties.ingestion().group()
        ).getTotalPendingMessages());
    }

    private void assertGroupExists(RedisExecutionProperties.Workload workload) {
        assertTrue(redis.opsForStream()
                .groups(workload.stream())
                .stream()
                .anyMatch(group -> workload.group().equals(group.groupName())));
    }
}
