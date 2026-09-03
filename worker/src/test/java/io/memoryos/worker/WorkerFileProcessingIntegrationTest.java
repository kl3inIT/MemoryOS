package io.memoryos.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.memoryos.connector.SourceManagementService;
import io.memoryos.connector.SourceStatus;
import io.memoryos.identity.ActorId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.locks.LockSupport;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:db/migration/V1__create_identity_tables.sql,"
                        + "classpath:db/migration/V2__create_initial_organization_and_sessions.sql,"
                        + "classpath:db/migration/V3__create_organization_invitations.sql,"
                        + "classpath:db/migration/V4__collapse_workspace_into_organization.sql,"
                        + "classpath:db/migration/V5__create_file_source_and_document_schema.sql,"
                        + "classpath:db/migration/V6__cut_over_organization_to_tenant.sql,"
                        + "classpath:db/migration/V7__create_scheduler_control_plane.sql,"
                        + "classpath:db/migration/V8__cut_over_operations_to_redis_streams.sql",
                "db-scheduler.enabled=true",
                "db-scheduler.scheduler-name=redis-cutover-integration",
                "db-scheduler.polling-interval=50ms",
                "management.endpoint.health.group.readiness.include=readinessState,db,redis,dbScheduler",
                "memoryos.worker.enabled=true",
                "memoryos.redis.relay-interval=50ms",
                "memoryos.redis.rediscovery-delay=750ms",
                "memoryos.redis.transport-backoff=100ms",
                "memoryos.redis.consumer-block=100ms",
                "memoryos.redis.reclaim-interval=250ms",
                "memoryos.redis.reclaim-idle=500ms",
                "memoryos.redis.ingestion.stream=memoryos:test:cutover:ingestion",
                "memoryos.redis.ingestion.group=memoryos-test-cutover-ingestion",
                "memoryos.redis.ingestion.batch-size=4",
                "memoryos.redis.cleanup.stream=memoryos:test:cutover:cleanup",
                "memoryos.redis.cleanup.group=memoryos-test-cutover-cleanup",
                "memoryos.redis.cleanup.batch-size=4"
        }
)
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection", "unchecked"})
class WorkerFileProcessingIntegrationTest {

    private static final String INGESTION_STREAM = "memoryos:test:cutover:ingestion";
    private static final String INGESTION_GROUP = "memoryos-test-cutover-ingestion";
    private static final String CLEANUP_STREAM = "memoryos:test:cutover:cleanup";
    private static final String CLEANUP_GROUP = "memoryos-test-cutover-cleanup";
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
    ).asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("memoryos")
            .withUsername("memoryos")
            .withPassword("memoryos");

    private static final ActorId OWNER = new ActorId(
            UUID.fromString("ac009796-bf52-4d3b-b619-acbde4e46717")
    );
    private static final UUID TENANT_ID =
            UUID.fromString("4595ef61-4758-4dcf-982a-a0d69ceec87f");


    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SourceManagementService sources;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisStreamWorker worker;

    @Autowired
    private RedisExecutionTopology topology;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }


    @BeforeEach
    void seedOwner() {
        UUID tenantId = TENANT_ID;
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:id)").param("id", OWNER.value()).update();
        jdbcClient.sql("""
                        INSERT INTO tenants (
                            id, slug, display_name, status, bootstrap_reference
                        ) VALUES (
                            :id, 'worker-file', 'Worker file', 'ACTIVE', 'MEM-35-WORKER-TEST'
                        )
                        """)
                .param("id", tenantId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO tenant_memberships (
                            tenant_id, actor_id, role, status
                        ) VALUES (:tenantId, :actorId, 'OWNER', 'ACTIVE')
                        """)
                .param("tenantId", tenantId)
                .param("actorId", OWNER.value())
                .update();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void redisStreamsIndexRemoveAndDeleteOneRealFile() throws Exception {
        worker.stop();
        var sourceId = sources.createFileSource(OWNER, "Worker knowledge").source().id();
        byte[] content = "MemoryOS worker extraction".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var upload = sources.upload(OWNER, sourceId, "worker.txt", content);
        assertEquals(sha256, upload.item().sha256());
        await(() -> redis.opsForStream().size(INGESTION_STREAM) == 1L);
        redis.delete(INGESTION_STREAM);
        topology.reconcileTopology();
        assertEquals(0L, redis.opsForStream().size(INGESTION_STREAM));
        await(() -> redis.opsForStream().size(INGESTION_STREAM) == 1L);
        var abandoned = redis.opsForStream().read(
                Consumer.from(INGESTION_GROUP, "abandoned-consumer"),
                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                StreamOffset.create(INGESTION_STREAM, ReadOffset.lastConsumed())
        );
        assertEquals(1, abandoned.size());
        assertEquals(
                1L,
                redis.opsForStream().pending(INGESTION_STREAM, INGESTION_GROUP).getTotalPendingMessages()
        );
        LockSupport.parkNanos(Duration.ofMillis(600).toNanos());
        worker.start();

        await(() -> sources.getSource(OWNER, sourceId).source().status() == SourceStatus.ACTIVE);
        assertEquals(1L, sources.getSource(OWNER, sourceId).source().documentCount());
        assertEquals(
                2,
                jdbcClient.sql("SELECT dispatch_attempts FROM index_attempts WHERE id = :id")
                        .param("id", upload.operation().id().value())
                        .query(Integer.class)
                        .single()
        );
        assertEquals(
                0L,
                redis.opsForStream().pending(
                        INGESTION_STREAM,
                        INGESTION_GROUP
                ).getTotalPendingMessages()
        );
        UUID deliveryId = jdbcClient.sql("SELECT delivery_id FROM index_attempts WHERE id = :id")
                .param("id", upload.operation().id().value())
                .query(UUID.class)
                .single();
        redis.opsForStream().add(INGESTION_STREAM, Map.of(
                "tenant_id", TENANT_ID.toString(),
                "operation_kind", "INGESTION",
                "operation_id", upload.operation().id().value().toString(),
                "delivery_id", deliveryId.toString()
        ));
        await(() -> redis.opsForStream().size(INGESTION_STREAM) == 0L);
        assertEquals(
                1,
                jdbcClient.sql("SELECT processing_attempts FROM index_attempts WHERE id = :id")
                        .param("id", upload.operation().id().value())
                        .query(Integer.class)
                        .single()
        );

        sources.removeItem(OWNER, sourceId, upload.item().id());
        await(() -> sources.getSource(OWNER, sourceId).items().isEmpty());

        sources.deleteSource(OWNER, sourceId);
        jdbcClient.sql("""
                        UPDATE tenants SET status = 'INACTIVE', updated_at = CURRENT_TIMESTAMP
                        WHERE id = :tenantId
                        """)
                .param("tenantId", TENANT_ID)
                .update();
        await(() -> jdbcClient.sql("""
                        SELECT COUNT(*) FROM connector_credential_pairs
                        WHERE id = :sourceId
                        """)
                .param("sourceId", sourceId.value())
                .query(Integer.class)
                .single() == 0);
        assertEquals(
                "SUCCEEDED",
                jdbcClient.sql("""
                                SELECT status FROM connector_cleanup_attempts
                                WHERE target_pair_id = :sourceId AND operation = 'DELETE_SOURCE'
                                """)
                        .param("sourceId", sourceId.value())
                        .query(String.class)
                        .single()
        );
        assertEquals(
                0L,
                redis.opsForStream().pending(
                        CLEANUP_STREAM,
                        CLEANUP_GROUP
                ).getTotalPendingMessages()
        );
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("worker condition did not converge within 10 seconds");
            }
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
        }
    }
}
