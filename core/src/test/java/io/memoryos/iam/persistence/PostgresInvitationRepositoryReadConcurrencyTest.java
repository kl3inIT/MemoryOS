package io.memoryos.iam.persistence;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.iam.TenantId;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresInvitationRepositoryReadConcurrencyTest {

    @Test
    void readOnlyLookupsDoNotWaitForJpaLifecycleRowLock() throws Exception {
        var dataSource = TestDatabase.freshPostgres();
        var jdbcClient = JdbcClient.create(dataSource);
        try (JpaHarness jpa = TestDatabase.jpa(dataSource)) {
            var repository = new JpaInvitationRepository(jpa.entityManager());
            var transaction = new TransactionTemplate(jpa.transactionManager());
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
                            ignored -> repository.findByDigest(digest).orElseThrow().getId()
                    ));
                    var byId = executor.submit(() -> transaction.execute(
                            ignored -> repository.find(tenantId, invitationId).orElseThrow().getId()
                    ));

                    assertEquals(invitationId, byDigest.get(2, SECONDS));
                    assertEquals(invitationId, byId.get(2, SECONDS));
                } finally {
                    releaseLock.countDown();
                    locker.get(10, SECONDS);
                }
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
                        VALUES (:id, :slug, 'Test Tenant', 'ACTIVE', 'TEST-READ-LOCK')
                        """)
                .param("id", tenantId.value())
                .param("slug", "test-" + tenantId.value().toString().substring(0, 8))
                .update();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-06T00:00:00Z");
        jdbcClient.sql("""
                        INSERT INTO tenant_invitations (
                            id, tenant_id, normalized_email, open_email_key, secret_digest,
                            status, created_by_actor_id, created_at, updated_at, expires_at
                        ) VALUES (
                            :id, :tenantId, 'member@example.com', 'member@example.com', :digest,
                            'PENDING', :actorId, :now, :now, :expiresAt
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
