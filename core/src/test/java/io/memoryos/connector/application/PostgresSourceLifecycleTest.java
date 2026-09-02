package io.memoryos.connector.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceManagementService;
import io.memoryos.TestDatabase;
import io.memoryos.connector.persistence.JdbcCleanupAttemptRepository;
import io.memoryos.connector.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.persistence.JdbcSourceQueryRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.DocumentId;
import io.memoryos.document.persistence.JdbcDocumentRepository;
import io.memoryos.identity.ActorId;
import io.memoryos.tenant.persistence.JdbcTenantAccessResolver;
import io.memoryos.tenant.TenantId;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresSourceLifecycleTest {

    private JdbcClient jdbcClient;
    private DriverManagerDataSource dataSource;
    private DataSourceTransactionManager transactionManager;
    private ObjectMapper objectMapper;
    private SourceManagementService service;
    private JdbcIndexAttemptRepository attempts;
    private ActorId owner;
    private JdbcCleanupAttemptRepository cleanup;

    private UUID tenantId;

    @BeforeEach
    void migrateAndSeed() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbcClient = JdbcClient.create(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        objectMapper = new ObjectMapper();
        tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:id)").param("id", actorId).update();
        jdbcClient.sql("""
                        INSERT INTO tenants (
                            id, slug, display_name, status, bootstrap_reference
                        ) VALUES (
                            :id, 'source-concurrency', 'Source concurrency', 'ACTIVE', 'MEM-35-TEST'
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
                .param("actorId", actorId)
                .update();
        owner = new ActorId(actorId);

        var sourceRepository = new JdbcSourceRepository(jdbcClient);
        var sourceDocuments = new JdbcSourceDocumentRepository(jdbcClient);
        attempts = new JdbcIndexAttemptRepository(jdbcClient, sourceRepository, sourceDocuments);
        var documents = new JdbcDocumentRepository(jdbcClient, objectMapper);
        cleanup = new JdbcCleanupAttemptRepository(jdbcClient, sourceRepository, sourceDocuments, documents);
        service = service(sourceRepository);
    }

    @Test
    void concurrentSourceCreationSharesOneNoAuthCredential() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.createFileSource(owner, "First"));
            var second = executor.submit(() -> service.createFileSource(owner, "Second"));
            first.get();
            second.get();
        }
        assertEquals(1L, count("credentials"));
        assertEquals(2L, count("connectors"));
        assertEquals(2L, count("connector_credential_pairs"));
    }

    @Test
    void duplicateUploadConvergesOnOneItemVersionAndAttempt() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Files").source().id();
        byte[] content = "same MemoryOS content".getBytes(StandardCharsets.UTF_8);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.upload(owner, sourceId, "first.txt", content));
            var second = executor.submit(() -> service.upload(owner, sourceId, "second.txt", content));
            var firstResult = first.get();
            var secondResult = second.get();
            assertEquals(firstResult.item().id(), secondResult.item().id());
            assertEquals(firstResult.operation().id(), secondResult.operation().id());
        }
        assertEquals(1L, count("connector_items"));
        assertEquals(1L, count("connector_item_versions"));
        assertEquals(1L, count("index_attempts"));
    }

    @Test
    void staleWorkerTokenCannotCompleteAfterLeaseReclaim() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Lease").source().id();
        byte[] content = "lease content".getBytes(StandardCharsets.UTF_8);
        service.upload(owner, sourceId, "lease.txt", content);
        var stale = attempts.claim(1).getFirst();
        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE id = :id
                        """)
                .param("id", stale.operationId().value())
                .update();
        var current = attempts.claim(1).getFirst();
        assertNotEquals(stale.claimToken(), current.claimToken());
        DocumentId documentId = new DocumentId(UUID.randomUUID());
        jdbcClient.sql("""
                        INSERT INTO documents (id, tenant_id, status)
                        VALUES (:id, :tenantId, 'ELIGIBLE')
                        """)
                .param("id", documentId.value())
                .param("tenantId", tenantId)
                .update();
        assertFalse(attempts.complete(stale, documentId));
        assertTrue(attempts.complete(current, documentId));
        assertEquals(1L, count("documents_by_connector_credential_pair"));

    }

    @Test
    void uploadRejectsAnItemWhoseRemovalIsPending() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Deleting item").source().id();
        byte[] content = "pending removal".getBytes(StandardCharsets.UTF_8);
        var upload = service.upload(owner, sourceId, "pending.txt", content);
        service.removeItem(owner, sourceId, upload.item().id());

        SourceException exception = assertThrows(
                SourceException.class,
                () -> service.upload(owner, sourceId, "duplicate.txt", content)
        );

        assertEquals("SOURCE_CONFLICT", exception.code());
    }

    @Test
    void persistsExtractionMetadataWithTheDocumentVersion() {
        var documents = new JdbcDocumentRepository(jdbcClient, objectMapper);
        documents.publish(
                new TenantId(tenantId),
                null,
                new DocumentContent(
                        "text/plain",
                        "Metadata",
                        "Metadata content",
                        Map.of("author", "MemoryOS", "page", "7")
                ),
                "a".repeat(64)
        );

        String metadata = jdbcClient.sql("SELECT metadata_json FROM document_versions")
                .query(String.class)
                .single();
        assertEquals("MemoryOS", objectMapper.readTree(metadata).path("author").stringValue());
        assertEquals("7", objectMapper.readTree(metadata).path("page").stringValue());
    }

    @Test
    void removeRechecksSourceDeletionAfterWaitingForTheSourceLock() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Cleanup race").source().id();
        byte[] content = "cleanup race".getBytes(StandardCharsets.UTF_8);
        var upload = service.upload(owner, sourceId, "race.txt", content);
        var coordinatedRepository = new CoordinatedSourceRepository(jdbcClient);
        SourceManagementService coordinatedService = service(coordinatedRepository);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var remove = executor.submit(
                    () -> coordinatedService.removeItem(owner, sourceId, upload.item().id())
            );
            assertTrue(coordinatedRepository.awaitLock());
            var delete = service.deleteSource(owner, sourceId);
            coordinatedRepository.releaseLock();

            assertEquals(delete.id(), remove.get().id());
            assertEquals(SourceOperationType.DELETE_SOURCE, remove.get().type());
        } finally {
            coordinatedRepository.releaseLock();
        }
        assertEquals(
                0L,
                jdbcClient.sql("""
                                SELECT COUNT(*) FROM connector_cleanup_attempts
                                WHERE operation = 'REMOVE_ITEM'
                                """)
                        .query(Long.class)
                        .single()
        );
    }

    @Test
    void concurrentReindexAndCleanupLeaseReclaimRemainSingleFlight() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Single flight").source().id();
        byte[] content = "single flight".getBytes(StandardCharsets.UTF_8);
        var upload = service.upload(owner, sourceId, "single.txt", content);
        var initialWork = attempts.claim(1).getFirst();
        assertTrue(attempts.fail(initialWork, "SOURCE_EXTRACTION_TIMEOUT"));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.reindex(owner, sourceId, upload.item().id()));
            var second = executor.submit(() -> service.reindex(owner, sourceId, upload.item().id()));
            assertEquals(first.get().id(), second.get().id());
        }
        assertEquals(2L, count("index_attempts"));

        service.deleteSource(owner, sourceId);
        var staleCleanup = cleanup.claim(1).getFirst();
        jdbcClient.sql("""
                        UPDATE connector_cleanup_attempts
                        SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE id = :id
                        """)
                .param("id", staleCleanup.operationId().value())
                .update();
        var currentCleanup = cleanup.claim(1).getFirst();
        assertNotEquals(staleCleanup.claimToken(), currentCleanup.claimToken());
        assertFalse(cleanup.fail(staleCleanup, "SOURCE_CLEANUP_INTERNAL"));
        assertTrue(cleanup.fail(currentCleanup, "SOURCE_CLEANUP_INTERNAL"));
    }

    @Test
    void inactiveTenantCancelsPendingIndexWorkWithoutPublishing() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Inactive").source().id();
        byte[] content = "inactive content".getBytes(StandardCharsets.UTF_8);
        service.upload(owner, sourceId, "inactive.txt", content);
        jdbcClient.sql("""
                        UPDATE tenants SET status = 'INACTIVE', updated_at = CURRENT_TIMESTAMP
                        WHERE id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .update();

        assertTrue(attempts.claim(1).isEmpty());
        assertEquals(
                "CANCELLED",
                jdbcClient.sql("SELECT status FROM index_attempts")
                        .query(String.class)
                        .single()
        );
        assertEquals(0L, count("documents"));
        assertEquals(0L, count("documents_by_connector_credential_pair"));
    }

    private SourceManagementService service(JdbcSourceRepository sourceRepository) {
        var sourceDocuments = new JdbcSourceDocumentRepository(jdbcClient);
        var target = new DefaultSourceManagementService(
                sourceRepository,
                new JdbcSourceItemRepository(jdbcClient),
                new JdbcIndexAttemptRepository(jdbcClient, sourceRepository, sourceDocuments),
                sourceDocuments,
                new JdbcSourceQueryRepository(jdbcClient),
                new JdbcTenantAccessResolver(jdbcClient)
        );
        return TestDatabase.transactionalProxy(target, SourceManagementService.class, transactionManager);
    }

    private static final class CoordinatedSourceRepository extends JdbcSourceRepository {

        private final CountDownLatch lockReached = new CountDownLatch(1);
        private final CountDownLatch allowLock = new CountDownLatch(1);

        private CoordinatedSourceRepository(JdbcClient jdbcClient) {
            super(jdbcClient);
        }

        @Override
        public SourcePair lock(TenantId tenantId, SourceId sourceId) {
            lockReached.countDown();
            try {
                if (!allowLock.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release the source lock");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while coordinating the source lock", exception);
            }
            return super.lock(tenantId, sourceId);
        }

        private boolean awaitLock() throws InterruptedException {
            return lockReached.await(5, TimeUnit.SECONDS);
        }

        private void releaseLock() {
            allowLock.countDown();
        }
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

}
