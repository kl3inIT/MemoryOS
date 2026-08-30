package io.memoryos.invitation.persistence;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.tenant.TenantId;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresInvitationRepositoryReadConcurrencyTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
    ).asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("memoryos")
            .withUsername("memoryos")
            .withPassword("memoryos");

    @Test
    void readOnlyLookupsDoNotWaitForLifecycleRowLock() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        try (var connection = dataSource.getConnection()) {
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/V1__create_identity_tables.sql"),
                    new ClassPathResource("db/migration/V2__create_initial_organization_and_sessions.sql"),
                    new ClassPathResource("db/migration/V3__create_organization_invitations.sql"),
                    new ClassPathResource("db/migration/V4__collapse_workspace_into_organization.sql"),
                    new ClassPathResource("db/migration/V5__create_file_source_and_document_schema.sql"),
                    new ClassPathResource("db/migration/V6__cut_over_organization_to_tenant.sql")
            ).populate(connection);
        }

        var jdbcClient = JdbcClient.create(dataSource);
        var repository = new JdbcInvitationRepository(jdbcClient);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var tenantId = new TenantId(UUID.randomUUID());
        var actorId = UUID.randomUUID();
        var invitationId = UUID.randomUUID();
        var digest = "a".repeat(64);
        insertPendingInvitation(jdbcClient, actorId, tenantId, invitationId, digest);

        var lockHeld = new CountDownLatch(1);
        var releaseLock = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var locker = executor.submit(() -> transaction.executeWithoutResult(ignored -> {
                repository.findLocked(tenantId, invitationId).orElseThrow();
                lockHeld.countDown();
                await(releaseLock);
            }));
            try {
                assertTrue(lockHeld.await(10, SECONDS));
                var byDigest = executor.submit(() -> transaction.execute(
                        ignored -> repository.findByDigest(digest).orElseThrow()
                ));
                var byId = executor.submit(() -> transaction.execute(
                        ignored -> repository.find(tenantId, invitationId).orElseThrow()
                ));

                assertEquals(invitationId, byDigest.get(2, SECONDS).id());
                assertEquals(invitationId, byId.get(2, SECONDS).id());
            } finally {
                releaseLock.countDown();
                locker.get(10, SECONDS);
            }
        }
    }

    private static void insertPendingInvitation(
            JdbcClient jdbcClient,
            UUID actorId,
            TenantId tenantId,
            UUID invitationId,
            String digest
    ) {
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", actorId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference)
                        VALUES (:id, 'test', 'Test Tenant', 'ACTIVE', 'TEST-READ-LOCK')
                        """)
                .param("id", tenantId.value())
                .update();
        OffsetDateTime now = OffsetDateTime.parse("2026-08-27T00:00:00Z");
        jdbcClient.sql("""
                        INSERT INTO tenant_invitations (
                            id,
                            tenant_id,
                            normalized_email,
                            open_email_key,
                            secret_digest,
                            status,
                            created_by_actor_id,
                            created_at,
                            updated_at,
                            expires_at
                        ) VALUES (
                            :id,
                            :tenantId,
                            'member@example.com',
                            'member@example.com',
                            :digest,
                            'PENDING',
                            :actorId,
                            :now,
                            :now,
                            :expiresAt
                        )
                        """)
                .param("id", invitationId)
                .param("tenantId", tenantId.value())
                .param("digest", digest)
                .param("actorId", actorId)
                .param("now", now)
                .param("expiresAt", now.plusHours(1))
                .update();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, SECONDS)) {
                throw new IllegalStateException("timed out while holding invitation row lock");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding invitation row lock", exception);
        }
    }
}
