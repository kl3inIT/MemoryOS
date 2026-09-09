package io.memoryos.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "arconia.dev.services.redis.enabled=false",
                "memoryos.worker.enabled=false",
                "db-scheduler.enabled=false",
                "management.endpoint.health.group.readiness.include=readinessState,db,redis",
                "memoryos.redis.ingestion.stream=memoryos:test:work:ingestion",
                "memoryos.redis.ingestion.group=memoryos-test-ingestion-workers",
                "memoryos.redis.cleanup.stream=memoryos:test:work:cleanup",
                "memoryos.redis.cleanup.group=memoryos-test-cleanup-workers",
                "memoryos.redis.search.stream=memoryos:test:work:search",
                "memoryos.redis.search.group=memoryos-test-search-workers",
                "memoryos.redis.source-sync.stream=memoryos:test:work:source-sync",
                "memoryos.redis.source-sync.group=memoryos-test-source-sync-workers",
                "memoryos.redis.selection-validation.stream=memoryos:test:work:selection-validation",
                "memoryos.redis.selection-validation.group=memoryos-test-selection-validation-workers",
                "spring.data.redis.repositories.enabled=false"
        }
)
@AutoConfigureTestRestTemplate
@Testcontainers
class RedisExecutionTopologyIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse(
                    "redis:8.2.1-alpine@sha256:987c376c727652f99625c7d205a1cba3cb2c53b92b0b62aade2bd48ee1593232"
            )
    )
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1))
            .withStartupTimeout(Duration.ofSeconds(30));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        WorkerPostgresDatabase.configure(registry);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(io.memoryos.ingestion.OperationWorkload.class)
    void createsGroupsAndAcknowledgesIdentifierOnlyDelivery(io.memoryos.ingestion.OperationWorkload kind) {
        var workload = properties.workload(kind);
        topology.ensureTopology();
        topology.ensureTopology();

        assertGroupExists(workload);
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
