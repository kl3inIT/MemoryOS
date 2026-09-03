package io.memoryos.connector.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.memoryos.TestDatabase;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceManagementService;
import io.memoryos.connector.SourceOperationType;
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
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.ingestion.persistence.JdbcOperationDispatchRepository;
import io.memoryos.tenant.TenantId;
import io.memoryos.tenant.persistence.JdbcTenantAccessResolver;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresSourceLifecycleTest {

    private JdbcClient jdbcClient;
    private DataSourceTransactionManager transactionManager;
    private ObjectMapper objectMapper;
    private SourceManagementService service;
    private JdbcIndexAttemptRepository attempts;
    private ActorId owner;
    private JdbcCleanupAttemptRepository cleanup;
    private OperationDispatchPort operationDispatch;

    private UUID tenantId;

    @BeforeEach
    void migrateAndSeed() throws Exception {
        var dataSource = TestDatabase.freshPostgres();
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
        operationDispatch = TestDatabase.transactionalProxy(
                new JdbcOperationDispatchRepository(jdbcClient),
                OperationDispatchPort.class,
                transactionManager
        );
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
    void concurrentRelayClaimsOnceAndRediscoveryRepublishesFromPostgres() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Relay").source().id();
        var upload = service.upload(owner, sourceId, "relay.txt", "relay content".getBytes(StandardCharsets.UTF_8));

        int claimed;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> operationDispatch.claim(OperationWorkload.INGESTION, 1));
            var second = executor.submit(() -> operationDispatch.claim(OperationWorkload.INGESTION, 1));
            claimed = first.get().size() + second.get().size();
        }
        assertEquals(1, claimed);
        assertEquals(1, jdbcClient.sql("SELECT dispatch_attempts FROM index_attempts")
                .query(Integer.class)
                .single());

        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET dispatch_token = NULL,
                            dispatch_lease_expires_at = NULL,
                            next_dispatch_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE id = :operationId
                        """)
                .param("operationId", upload.operation().id().value())
                .update();
        OperationDelivery rediscovered = dispatch(OperationWorkload.INGESTION);
        assertEquals(2, jdbcClient.sql("SELECT dispatch_attempts FROM index_attempts")
                .query(Integer.class)
                .single());
        assertEquals(new TenantId(tenantId), rediscovered.tenantId());
    }

    @Test
    void transportFailureDefersWithoutFailingTheOperation() {
        SourceId sourceId = service.createFileSource(owner, "Transport").source().id();
        service.upload(owner, sourceId, "transport.txt", "transport".getBytes(StandardCharsets.UTF_8));
        var claim = operationDispatch.claim(OperationWorkload.INGESTION, 1).getFirst();

        assertTrue(operationDispatch.defer(
                claim,
                "REDIS_TRANSPORT_UNAVAILABLE",
                Duration.ofHours(1)
        ));
        assertTrue(operationDispatch.claim(OperationWorkload.INGESTION, 1).isEmpty());
        assertEquals(
                "NOT_STARTED:REDIS_TRANSPORT_UNAVAILABLE",
                jdbcClient.sql("SELECT status || ':' || last_transport_error FROM index_attempts")
                        .query(String.class)
                        .single()
        );
    }

    @Test
    void unexpectedProcessingFailureRetriesThenTerminatesDurably() {
        SourceId sourceId = service.createFileSource(owner, "Retry").source().id();
        service.upload(owner, sourceId, "retry.txt", "retry".getBytes(StandardCharsets.UTF_8));

        for (int attempt = 1; attempt <= 3; attempt++) {
            if (attempt > 1) {
                jdbcClient.sql("""
                                UPDATE index_attempts
                                SET next_dispatch_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                                WHERE connector_credential_pair_id = :sourceId
                                """)
                        .param("sourceId", sourceId.value())
                        .update();
            }
            OperationDelivery delivery = dispatch(OperationWorkload.INGESTION);
            var work = attempts.claim(
                    delivery.tenantId(),
                    delivery.operationId(),
                    delivery.deliveryId()
            ).orElseThrow();
            assertTrue(attempts.retry(
                    work,
                    "SOURCE_EXTRACTION_INTERNAL",
                    3,
                    Duration.ofHours(1)
            ));
        }

        assertEquals(
                "FAILED:3",
                jdbcClient.sql("SELECT status || ':' || processing_attempts FROM index_attempts")
                        .query(String.class)
                        .single()
        );
        assertEquals(
                "FAILED",
                jdbcClient.sql("""
                                SELECT status FROM connector_credential_pairs
                                WHERE id = :sourceId
                                """)
                        .param("sourceId", sourceId.value())
                        .query(String.class)
                        .single()
        );
    }


    @Test
    void staleWorkerTokenCannotCompleteAfterLeaseReclaim() {
        SourceId sourceId = service.createFileSource(owner, "Lease").source().id();
        byte[] content = "lease content".getBytes(StandardCharsets.UTF_8);
        service.upload(owner, sourceId, "lease.txt", content);
        OperationDelivery delivery = dispatch(OperationWorkload.INGESTION);
        var stale = attempts.claim(delivery.tenantId(), delivery.operationId(), delivery.deliveryId()).orElseThrow();
        assertTrue(attempts.renew(stale));
        assertFalse(operationDispatch.reclaimable(delivery));
        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE id = :id
                        """)
                .param("id", stale.operationId().value())
                .update();
        assertTrue(operationDispatch.reclaimable(delivery));
        var current = attempts.claim(
                delivery.tenantId(),
                delivery.operationId(),
                delivery.deliveryId()
        ).orElseThrow();
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
    void uploadRejectsAnItemWhoseRemovalIsPending() {
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
        OperationDelivery initialDelivery = dispatch(OperationWorkload.INGESTION);
        var initialWork = attempts.claim(
                initialDelivery.tenantId(),
                initialDelivery.operationId(),
                initialDelivery.deliveryId()
        ).orElseThrow();
        assertTrue(attempts.fail(initialWork, "SOURCE_EXTRACTION_TIMEOUT"));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.reindex(owner, sourceId, upload.item().id()));
            var second = executor.submit(() -> service.reindex(owner, sourceId, upload.item().id()));
            assertEquals(first.get().id(), second.get().id());
        }
        assertEquals(2L, count("index_attempts"));

        service.deleteSource(owner, sourceId);
        OperationDelivery cleanupDelivery = dispatch(OperationWorkload.CLEANUP);
        var staleCleanup = cleanup.claim(
                cleanupDelivery.tenantId(),
                cleanupDelivery.operationId(),
                cleanupDelivery.deliveryId()
        ).orElseThrow();
        jdbcClient.sql("""
                        UPDATE connector_cleanup_attempts
                        SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE id = :id
                        """)
                .param("id", staleCleanup.operationId().value())
                .update();
        var currentCleanup = cleanup.claim(
                cleanupDelivery.tenantId(),
                cleanupDelivery.operationId(),
                cleanupDelivery.deliveryId()
        ).orElseThrow();
        assertNotEquals(staleCleanup.claimToken(), currentCleanup.claimToken());
        assertFalse(cleanup.fail(staleCleanup, "SOURCE_CLEANUP_INTERNAL"));
        assertTrue(cleanup.fail(currentCleanup, "SOURCE_CLEANUP_INTERNAL"));
    }

    @Test
    void inactiveTenantCancelsPendingIndexWorkWithoutPublishing() {
        SourceId sourceId = service.createFileSource(owner, "Inactive").source().id();
        byte[] content = "inactive content".getBytes(StandardCharsets.UTF_8);
        service.upload(owner, sourceId, "inactive.txt", content);
        service.upload(owner, sourceId, "second.txt", "second".getBytes(StandardCharsets.UTF_8));
        jdbcClient.sql("""
                        UPDATE tenants SET status = 'INACTIVE', updated_at = CURRENT_TIMESTAMP
                        WHERE id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .update();

        assertEquals(1, operationDispatch.cancelInactiveTenantIndexing(1));
        assertEquals(
                1,
                jdbcClient.sql("SELECT COUNT(*) FROM index_attempts WHERE status = 'CANCELLED'")
                        .query(Integer.class)
                        .single()
        );
        assertEquals(
                1,
                jdbcClient.sql("SELECT COUNT(*) FROM index_attempts WHERE status = 'NOT_STARTED'")
                        .query(Integer.class)
                        .single()
        );
        assertTrue(operationDispatch.claim(OperationWorkload.INGESTION, 1).isEmpty());
        assertEquals(1, operationDispatch.cancelInactiveTenantIndexing(1));
        assertEquals(
                2,
                jdbcClient.sql("SELECT COUNT(*) FROM index_attempts WHERE status = 'CANCELLED'")
                        .query(Integer.class)
                        .single()
        );
        assertEquals(0L, count("documents"));
        assertEquals(0L, count("documents_by_connector_credential_pair"));
    }

    private OperationDelivery dispatch(OperationWorkload workload) {
        return operationDispatch.claim(workload, 1).getFirst().delivery();
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
