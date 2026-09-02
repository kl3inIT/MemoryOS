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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
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
                        + "classpath:db/migration/V6__cut_over_organization_to_tenant.sql",
                "db-scheduler.enabled=false",
                "management.endpoint.health.group.readiness.include=readinessState,db,redis",
                "memoryos.worker.enabled=true",
                "memoryos.worker.batch-size=4",
                "memoryos.worker.poll-delay=25ms"
        }
)
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class WorkerFileProcessingIntegrationTest {

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
    void schedulerIndexesRemovesAndDeletesOneRealFile() throws Exception {
        var sourceId = sources.createFileSource(OWNER, "Worker knowledge").source().id();
        byte[] content = "MemoryOS worker extraction".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var upload = sources.upload(OWNER, sourceId, "worker.txt", content);

        await(() -> sources.getSource(OWNER, sourceId).source().status() == SourceStatus.ACTIVE);
        assertEquals(1L, sources.getSource(OWNER, sourceId).source().documentCount());

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
