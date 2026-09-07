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
                "arconia.dev.services.redis.port=0",
                "memoryos.worker.enabled=false",
                "db-scheduler.enabled=false",
                "management.endpoint.health.group.readiness.include=readinessState,db,redis",
                "memoryos.redis.ingestion.stream=memoryos:test:work:ingestion",
                "memoryos.redis.ingestion.group=memoryos-test-ingestion-workers",
                "memoryos.redis.cleanup.stream=memoryos:test:work:cleanup",
                "memoryos.redis.cleanup.group=memoryos-test-cleanup-workers",
                "memoryos.redis.source-sync.stream=memoryos:test:work:source-sync",
                "memoryos.redis.source-sync.group=memoryos-test-source-sync-workers",
                "spring.datasource.url=jdbc:h2:mem:redis-topology;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.data.redis.repositories.enabled=false"
        }
)
@AutoConfigureTestRestTemplate
@Testcontainers(disabledWithoutDocker = true)
class RedisExecutionTopologyIntegrationTest {

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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(io.memoryos.ingestion.OperationWorkload.class)
    void createsGroupsAndAcknowledgesIdentifierOnlyDelivery(io.memoryos.ingestion.OperationWorkload kind) {
        var workload = properties.workload(kind);
        topology.ensureTopology();
        topology.ensureTopology();

        assertGroupExists(properties.ingestion());
        assertGroupExists(properties.cleanup());
        assertGroupExists(properties.sourceSync());
        assertEquals(
                HttpStatus.OK,
                http.getForEntity("/actuator/health/readiness", String.class).getStatusCode()
        );
        var operations = redis.opsForStream();
        var recordId = operations.add(workload.stream(), Map.of(
                "tenant_id", UUID.randomUUID().toString(),
                "operation_kind", kind.name(),
                "operation_id", UUID.randomUUID().toString(),
                "delivery_id", UUID.randomUUID().toString()
        ));
        assertNotNull(recordId);

        var records = operations.read(
                Consumer.from(workload.group(), "mem42-integration"),
                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                StreamOffset.create(workload.stream(), ReadOffset.lastConsumed())
        );

        assertNotNull(records);
        assertEquals(1, records.size());
        assertEquals(1L, operations.pending(
                workload.stream(),
                workload.group()
        ).getTotalPendingMessages());
        assertEquals(1L, operations.acknowledge(
                workload.stream(),
                workload.group(),
                records.getFirst().getId()
        ));
        assertEquals(0L, operations.pending(
                workload.stream(),
                workload.group()
        ).getTotalPendingMessages());
    }

    private void assertGroupExists(RedisExecutionProperties.Workload workload) {
        assertTrue(redis.opsForStream()
                .groups(workload.stream())
                .stream()
                .anyMatch(group -> workload.group().equals(group.groupName())));
    }
}
