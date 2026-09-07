package io.memoryos.connector.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Duration;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.memoryos.TestDatabase;
import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceAction;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceManagementService;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.persistence.JdbcCleanupAttemptRepository;
import io.memoryos.connector.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceGroupRepository;
import io.memoryos.connector.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.persistence.JdbcSourceOperationQueryRepository;
import io.memoryos.connector.persistence.JdbcSourceQueryRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.connector.SourceUploadReceipt;
import io.memoryos.connector.persistence.JdbcSourceUploadRepository;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.DocumentId;
import io.memoryos.document.persistence.JdbcDocumentRepository;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.ObjectUploadAuthorization;
import io.memoryos.objectstorage.ObjectUploadCleanupPort;
import io.memoryos.objectstorage.ObjectUploadService;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.StoredObjectRegistry;
import io.memoryos.objectstorage.UploadAuthorization;
import io.memoryos.objectstorage.UploadConstraints;
import io.memoryos.objectstorage.application.DefaultObjectUploadService;
import io.memoryos.objectstorage.application.DefaultStoredObjectRegistry;
import io.memoryos.objectstorage.application.ObjectUploadProperties;
import io.memoryos.objectstorage.persistence.JdbcObjectUploadRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupSystemKey;
import io.memoryos.iam.IamException;
import io.memoryos.iam.application.DefaultGroupScopeService;
import io.memoryos.iam.application.DefaultIamAuthorization;
import io.memoryos.iam.persistence.GroupInvariantRepository;
import io.memoryos.iam.persistence.GroupProjectionRepository;
import io.memoryos.iam.persistence.IamAuthorizationRepository;
import io.memoryos.iam.persistence.IamLockRepository;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.ingestion.persistence.JdbcOperationDispatchRepository;
import io.memoryos.iam.TenantId;
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
    private ConnectorCleanupPort cleanup;
    private OperationDispatchPort operationDispatch;
    private InMemoryObjectStorage objectStorage;
    private ObjectUploadService objectUploads;
    private ObjectUploadCleanupPort objectUploadCleanup;
    private StoredObjectRegistry storedObjects;
    private JdbcSourceUploadRepository sourceUploads;

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
        seedGroups(actorId);
        owner = new ActorId(actorId);

        var sourceRepository = new JdbcSourceRepository(jdbcClient);
        var sourceDocuments = new JdbcSourceDocumentRepository(jdbcClient);
        attempts = new JdbcIndexAttemptRepository(jdbcClient, sourceRepository, sourceDocuments);
        var documents = new JdbcDocumentRepository(jdbcClient, objectMapper);
        sourceUploads = new JdbcSourceUploadRepository(jdbcClient);
        objectStorage = new InMemoryObjectStorage();
        var storedObjectRepository = new JdbcStoredObjectRepository(jdbcClient);
        var objectUploadService = new DefaultObjectUploadService(
                storedObjectRepository,
                new JdbcObjectUploadRepository(jdbcClient),
                objectStorage,
                new ObjectUploadProperties(
                        Duration.ofMinutes(15),
                        Duration.ofSeconds(30),
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(1),
                        16
                ),
                transactionManager
        );
        objectUploads = objectUploadService;
        objectUploadCleanup = objectUploadService;
        storedObjects = TestDatabase.transactionalProxy(
                new DefaultStoredObjectRegistry(storedObjectRepository),
                StoredObjectRegistry.class,
                transactionManager
        );
        cleanup = TestDatabase.transactionalProxy(
                new DefaultConnectorCleanupService(
                        new JdbcCleanupAttemptRepository(jdbcClient),
                        sourceRepository,
                        sourceDocuments,
                        sourceUploads,
                        documents,
                        objectUploads,
                        storedObjects
                ),
                ConnectorCleanupPort.class,
                transactionManager
        );
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
            var first = executor.submit(() -> service.createFileSource(owner, "First", List.of()));
            var second = executor.submit(() -> service.createFileSource(owner, "Second", List.of()));
            first.get();
            second.get();
        }
        assertEquals(1L, count("credentials"));
        assertEquals(2L, count("connectors"));
        assertEquals(2L, count("connector_credential_pairs"));
    }

    @Test
    void sourceCreationAssociatesAdminByDefaultAndRejectsUnknownGroupsAtomically() {
        var defaultSource = service.createFileSource(owner, "Admin source", List.of());
        var defaultGroups = service.listSourceGroups(owner, defaultSource.source().id());
        assertEquals(1, defaultGroups.size());
        assertEquals(GroupSystemKey.ADMIN, defaultGroups.getFirst().systemKey());

        long connectorCount = count("connectors");
        IamException failure = assertThrows(
                IamException.class,
                () -> service.createFileSource(
                        owner,
                        "Invalid source",
                        List.of(new GroupId(UUID.randomUUID()))
                )
        );
        assertEquals("IAM_GROUP_NOT_FOUND", failure.code());
        assertEquals(connectorCount, count("connectors"));
        assertEquals(connectorCount, count("connector_credential_pairs"));
        assertEquals(connectorCount, count("source_group_grants"));
    }

    @Test
    void scopedManagerReadsAndManagesOnlyAssociatedSources() {
        GroupId managedGroupId = new GroupId(UUID.randomUUID());
        ActorId manager = addScopedManager(managedGroupId);
        var managed = service.createFileSource(
                owner,
                "Managed source",
                List.of(managedGroupId)
        );
        var hidden = service.createFileSource(owner, "Hidden source", List.of());
        var managedUpload = upload(
                manager,
                managed.source().id(),
                "managed.txt",
                "managed content".getBytes(StandardCharsets.UTF_8)
        );
        var hiddenUpload = upload(
                owner,
                hidden.source().id(),
                "hidden.txt",
                "hidden content".getBytes(StandardCharsets.UTF_8)
        );

        var visible = service.listSources(manager);
        assertEquals(1, visible.size());
        assertEquals(managed.source().id(), visible.getFirst().id());
        assertEquals(
                List.of(SourceAction.UPLOAD, SourceAction.REINDEX),
                visible.getFirst().actions()
        );
        assertThrows(SourceException.class, () -> service.getSource(manager, hidden.source().id()));
        assertEquals(
                managedUpload.operation(),
                service.getOperation(manager, managedUpload.operation().id())
        );
        assertThrows(
                SourceException.class,
                () -> service.getOperation(manager, hiddenUpload.operation().id())
        );
        service.reindex(manager, managed.source().id(), managedUpload.item().id());
        IamException deleteDenied = assertThrows(
                IamException.class,
                () -> service.removeItem(manager, managed.source().id(), managedUpload.item().id())
        );
        assertEquals("IAM_ACCESS_DENIED", deleteDenied.code());
        assertThrows(
                IamException.class,
                () -> service.deleteSource(manager, managed.source().id())
        );
        assertThrows(
                IamException.class,
                () -> service.createFileSource(manager, "Denied", List.of(managedGroupId))
        );
        assertThrows(
                IamException.class,
                () -> service.replaceSourceGroups(
                        manager,
                        managed.source().id(),
                        List.of(managedGroupId)
                )
        );
        assertThrows(
                IamException.class,
                () -> service.listSourceGroupOptions(manager, "", 0, 25)
        );

        service.replaceSourceGroups(
                owner,
                managed.source().id(),
                List.of(adminGroupId())
        );
        assertTrue(service.listSources(manager).isEmpty());
        assertThrows(SourceException.class, () -> service.getSource(manager, managed.source().id()));
        assertThrows(
                SourceException.class,
                () -> service.getOperation(manager, managedUpload.operation().id())
        );
    }

    @Test
    void associationRevocationDuringProviderVerificationPreventsUploadCommit() throws Exception {
        GroupId managedGroupId = new GroupId(UUID.randomUUID());
        ActorId manager = addScopedManager(managedGroupId);
        SourceId sourceId = service.createFileSource(
                owner,
                "Revoked source",
                List.of(managedGroupId)
        ).source().id();
        byte[] content = "revoked during verification".getBytes(StandardCharsets.UTF_8);
        ObjectUploadAuthorization upload = service.initiateUpload(
                manager,
                sourceId,
                new ObjectUploadSpecification(
                        "revoked.txt",
                        "text/plain",
                        content.length,
                        checksum(content)
                )
        );
        objectStorage.put(upload.authorization().uri(), content);
        objectStorage.pauseNextInspection();

        try (var executor = Executors.newSingleThreadExecutor()) {
            var finalize = executor.submit(
                    () -> service.finalizeUpload(manager, sourceId, upload.uploadId())
            );
            assertTrue(objectStorage.awaitInspection());
            service.replaceSourceGroups(owner, sourceId, List.of(adminGroupId()));
            objectStorage.resumeInspection();

            ExecutionException failure = assertThrows(ExecutionException.class, finalize::get);
            assertInstanceOf(SourceException.class, failure.getCause());
        } finally {
            objectStorage.resumeInspection();
        }

        assertEquals(
                0L,
                jdbcClient.sql("""
                                SELECT COUNT(*)
                                FROM connector_items
                                WHERE tenant_id = :tenantId
                                """)
                        .param("tenantId", tenantId)
                        .query(Long.class)
                        .single()
        );
        assertEquals(
                0L,
                jdbcClient.sql("""
                                SELECT COUNT(*)
                                FROM source_uploads
                                WHERE tenant_id = :tenantId
                                  AND connector_credential_pair_id = :sourceId
                                  AND finalized_at IS NOT NULL
                                """)
                        .param("tenantId", tenantId)
                        .param("sourceId", sourceId.value())
                        .query(Long.class)
                        .single()
        );
    }

    @Test
    void duplicateUploadConvergesOnOneItemVersionAndAttempt() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Files", List.of()).source().id();
        byte[] content = "same MemoryOS content".getBytes(StandardCharsets.UTF_8);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> upload(owner, sourceId, "first.txt", content));
            var second = executor.submit(() -> upload(owner, sourceId, "second.txt", content));
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
    void finalizeReplayReturnsThePersistedReceiptWithoutAdoptingTwice() {
        SourceId sourceId = service.createFileSource(owner, "Lost response", List.of()).source().id();
        byte[] content = "lost finalize response".getBytes(StandardCharsets.UTF_8);
        ObjectUploadAuthorization authorization = service.initiateUpload(
                owner,
                sourceId,
                new ObjectUploadSpecification("lost.txt", "text/plain", content.length, checksum(content))
        );
        objectStorage.put(authorization.authorization().uri(), content);

        SourceUploadReceipt first = service.finalizeUpload(owner, sourceId, authorization.uploadId());
        SourceUploadReceipt replay = service.finalizeUpload(owner, sourceId, authorization.uploadId());

        assertEquals(first, replay);
        assertEquals(1L, count("source_uploads"));
        assertEquals(1L, count("object_uploads"));
        assertEquals(1L, count("stored_objects"));
    }

    @Test
    void duplicateDiscardAndAdoptedRemovalReleaseEveryObjectReference() {
        SourceId sourceId = service.createFileSource(owner, "Duplicate cleanup", List.of()).source().id();
        byte[] content = "duplicate cleanup content".getBytes(StandardCharsets.UTF_8);
        SourceUploadReceipt first = upload(owner, sourceId, "first.txt", content);
        SourceUploadReceipt duplicate = upload(owner, sourceId, "duplicate.txt", content);
        assertEquals(first.item().id(), duplicate.item().id());

        assertEquals(1, objectUploadCleanup.cleanupAbandoned());
        assertEquals(1L, count("stored_objects"));
        assertEquals(2L, count("object_uploads"));

        service.removeItem(owner, sourceId, first.item().id());
        OperationDelivery delivery = dispatch(OperationWorkload.CLEANUP);
        var work = cleanup.claim(delivery.tenantId(), delivery.operationId(), delivery.deliveryId()).orElseThrow();
        cleanup.objects(work).forEach(object -> {
            storedObjects.markDeletePending(work.tenantId(), object.object().id());
            objectStorage.delete(object.object().key());
        });
        assertTrue(cleanup.execute(work));

        assertEquals(0L, count("connector_items"));
        assertEquals(0L, count("connector_item_versions"));
        assertEquals(0L, count("stored_objects"));
        assertEquals(1L, count("object_uploads"));
        assertEquals(
                "EXPIRED",
                jdbcClient.sql("SELECT status FROM object_uploads").query(String.class).single()
        );
        assertEquals(0L, count("source_uploads"));
    }

    @Test
    void concurrentRelayClaimsOnceAndRediscoveryRepublishesFromPostgres() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Relay", List.of()).source().id();
        var upload = upload(owner, sourceId, "relay.txt", "relay content".getBytes(StandardCharsets.UTF_8));

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
        SourceId sourceId = service.createFileSource(owner, "Transport", List.of()).source().id();
        upload(owner, sourceId, "transport.txt", "transport".getBytes(StandardCharsets.UTF_8));
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
        SourceId sourceId = service.createFileSource(owner, "Retry", List.of()).source().id();
        upload(owner, sourceId, "retry.txt", "retry".getBytes(StandardCharsets.UTF_8));

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
            if (attempt == 1) {
                org.assertj.core.api.Assertions.assertThat(work.initialQueueWait()).isNotNull().isGreaterThanOrEqualTo(Duration.ZERO);
            } else {
                org.junit.jupiter.api.Assertions.assertNull(work.initialQueueWait());
            }
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
        SourceId sourceId = service.createFileSource(owner, "Lease", List.of()).source().id();
        byte[] content = "lease content".getBytes(StandardCharsets.UTF_8);
        upload(owner, sourceId, "lease.txt", content);
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
        org.assertj.core.api.Assertions.assertThat(stale.initialQueueWait()).isNotNull().isGreaterThanOrEqualTo(Duration.ZERO);
        org.junit.jupiter.api.Assertions.assertNull(current.initialQueueWait());
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
    void recoveredAttemptsClearHistoricalErrorsWithoutHidingOtherItemFailures() {
        SourceId sourceId = service.createFileSource(owner, "Recovery", List.of()).source().id();
        var uploads = List.of(
                upload(owner, sourceId, "first.txt", "first".getBytes(StandardCharsets.UTF_8)),
                upload(owner, sourceId, "second.txt", "second".getBytes(StandardCharsets.UTF_8))
        );
        for (int index = 0; index < uploads.size(); index++) {
            OperationDelivery delivery = dispatch(OperationWorkload.INGESTION);
            var work = attempts.claim(
                    delivery.tenantId(), delivery.operationId(), delivery.deliveryId()
            ).orElseThrow();
            assertTrue(attempts.fail(work, "SOURCE_EXTRACTION_TIMEOUT"));
        }

        for (int index = 0; index < uploads.size(); index++) {
            service.reindex(owner, sourceId, uploads.get(index).item().id());
            OperationDelivery delivery = dispatch(OperationWorkload.INGESTION);
            var work = attempts.claim(
                    delivery.tenantId(), delivery.operationId(), delivery.deliveryId()
            ).orElseThrow();
            DocumentId documentId = new DocumentId(UUID.randomUUID());
            jdbcClient.sql("INSERT INTO documents (id, tenant_id, status) VALUES (:id, :tenantId, 'ELIGIBLE')")
                    .param("id", documentId.value())
                    .param("tenantId", tenantId)
                    .update();
            assertTrue(attempts.complete(work, documentId));
            assertEquals(
                    index == 0 ? "SOURCE_EXTRACTION_TIMEOUT" : null,
                    service.getSource(owner, sourceId).source().errorCode()
            );
        }
        assertEquals(2L, jdbcClient.sql("SELECT COUNT(*) FROM index_attempts WHERE status = 'FAILED'")
                .query(Long.class).single());
    }

    @Test
    void uploadRejectsAnItemWhoseRemovalIsPending() {
        SourceId sourceId = service.createFileSource(owner, "Deleting item", List.of()).source().id();
        byte[] content = "pending removal".getBytes(StandardCharsets.UTF_8);
        var upload = upload(owner, sourceId, "pending.txt", content);
        service.removeItem(owner, sourceId, upload.item().id());

        SourceException exception = assertThrows(
                SourceException.class,
                () -> upload(owner, sourceId, "duplicate.txt", content)
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

        String metadata = jdbcClient.sql("SELECT metadata_json FROM documents")
                .query(String.class)
                .single();
        assertEquals("MemoryOS", objectMapper.readTree(metadata).path("author").stringValue());
        assertEquals("7", objectMapper.readTree(metadata).path("page").stringValue());
    }

    @Test
    void removeRechecksSourceDeletionAfterWaitingForTheSourceLock() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Cleanup race", List.of()).source().id();
        byte[] content = "cleanup race".getBytes(StandardCharsets.UTF_8);
        var upload = upload(owner, sourceId, "race.txt", content);
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
        SourceId sourceId = service.createFileSource(owner, "Single flight", List.of()).source().id();
        byte[] content = "single flight".getBytes(StandardCharsets.UTF_8);
        var upload = upload(owner, sourceId, "single.txt", content);
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
        org.assertj.core.api.Assertions.assertThat(staleCleanup.initialQueueWait()).isNotNull().isGreaterThanOrEqualTo(Duration.ZERO);
        org.junit.jupiter.api.Assertions.assertNull(currentCleanup.initialQueueWait());
        assertNotEquals(staleCleanup.claimToken(), currentCleanup.claimToken());
        assertFalse(cleanup.fail(staleCleanup, "SOURCE_CLEANUP_INTERNAL"));
        assertTrue(cleanup.fail(currentCleanup, "SOURCE_CLEANUP_INTERNAL"));
    }

    @Test
    void inactiveTenantCancelsPendingIndexWorkWithoutPublishing() {
        SourceId sourceId = service.createFileSource(owner, "Inactive", List.of()).source().id();
        byte[] content = "inactive content".getBytes(StandardCharsets.UTF_8);
        upload(owner, sourceId, "inactive.txt", content);
        upload(owner, sourceId, "second.txt", "second".getBytes(StandardCharsets.UTF_8));
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

    private SourceUploadReceipt upload(
            ActorId actor,
            SourceId sourceId,
            String filename,
            byte[] content
    ) {
        ContentSha256 checksum = checksum(content);
        ObjectUploadAuthorization authorization = service.initiateUpload(
                actor,
                sourceId,
                new ObjectUploadSpecification(filename, "text/plain", content.length, checksum)
        );
        objectStorage.put(authorization.authorization().uri(), content);
        return service.finalizeUpload(actor, sourceId, authorization.uploadId());
    }

    private void seedGroups(UUID actorId) {
        UUID adminGroupId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID basicGroupId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        jdbcClient.sql("""
                        INSERT INTO iam_groups (tenant_id, id, name, system_key)
                        VALUES (:tenantId, :adminGroupId, 'Admin', 'ADMIN'),
                               (:tenantId, :basicGroupId, 'Basic', 'BASIC')
                        """)
                .param("tenantId", tenantId)
                .param("adminGroupId", adminGroupId)
                .param("basicGroupId", basicGroupId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                        VALUES (:tenantId, :adminGroupId, 'IAM_ADMIN')
                        """)
                .param("tenantId", tenantId)
                .param("adminGroupId", adminGroupId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id)
                        VALUES (:tenantId, :adminGroupId, :actorId),
                               (:tenantId, :basicGroupId, :actorId)
                        """)
                .param("tenantId", tenantId)
                .param("adminGroupId", adminGroupId)
                .param("basicGroupId", basicGroupId)
                .param("actorId", actorId)
                .update();
    }

    private ActorId addScopedManager(GroupId groupId) {
        ActorId actorId = new ActorId(UUID.randomUUID());
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", actorId.value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                        VALUES (:tenantId, :actorId, 'MEMBER', 'ACTIVE')
                        """)
                .param("tenantId", tenantId)
                .param("actorId", actorId.value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO iam_groups (tenant_id, id, name)
                        VALUES (:tenantId, :groupId, :name)
                        """)
                .param("tenantId", tenantId)
                .param("groupId", groupId.value())
                .param("name", "Scoped " + groupId.value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO iam_group_memberships (
                            tenant_id, group_id, actor_id, is_manager
                        ) VALUES (
                            :tenantId, :basicGroupId, :actorId, FALSE
                        ), (
                            :tenantId, :groupId, :actorId, TRUE
                        )
                        """)
                .param("tenantId", tenantId)
                .param("basicGroupId", UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .param("groupId", groupId.value())
                .param("actorId", actorId.value())
                .update();
        return actorId;
    }

    private static GroupId adminGroupId() {
        return new GroupId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    private static ContentSha256 checksum(byte[] content) {
        try {
            return new ContentSha256(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)
            ));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private SourceManagementService service(JdbcSourceRepository sourceRepository) {
        var sourceDocuments = new JdbcSourceDocumentRepository(jdbcClient);
        var target = new DefaultSourceManagementService(
                sourceRepository,
                new JdbcSourceItemRepository(jdbcClient),
                new JdbcIndexAttemptRepository(jdbcClient, sourceRepository, sourceDocuments),
                sourceDocuments,
                new JdbcSourceQueryRepository(jdbcClient),
                new JdbcSourceOperationQueryRepository(jdbcClient),
                new JdbcSourceGroupRepository(jdbcClient),
                sourceUploads,
                objectUploads,
                new DefaultIamAuthorization(
                        new IamAuthorizationRepository(jdbcClient),
                        new IamLockRepository(jdbcClient)
                ),
                new DefaultGroupScopeService(
                        new GroupInvariantRepository(jdbcClient),
                        new GroupProjectionRepository(jdbcClient)
                ),
                transactionManager
        );
        return TestDatabase.transactionalProxy(target, SourceManagementService.class, transactionManager);
    }

    private static final class InMemoryObjectStorage implements ObjectStorage {
        @Override
        public void write(ObjectKey key, byte[] content, String mediaType) {
            throw new AssertionError("Connector lifecycle tests must not write extraction artifacts");
        }

        private final AtomicLong sequence = new AtomicLong();
        private final Map<URI, Entry> authorizations = new ConcurrentHashMap<>();
        private final Map<ObjectKey, Entry> objects = new ConcurrentHashMap<>();
        private volatile CountDownLatch inspectionStarted;
        private volatile CountDownLatch inspectionRelease;

        @Override
        public UploadAuthorization authorizeUpload(ObjectKey key, UploadConstraints constraints) {
            URI uri = URI.create("https://uploads.example.test/" + sequence.incrementAndGet());
            Entry entry = new Entry(constraints);
            authorizations.put(uri, entry);
            objects.put(key, entry);
            return new UploadAuthorization(
                    "PUT",
                    uri,
                    Map.of(
                            "content-type", constraints.mediaType(),
                            "x-amz-checksum-sha256", constraints.checksum().base64()
                    ),
                    Instant.now().plus(Duration.ofMinutes(10))
            );
        }

        void put(URI uri, byte[] content) {
            Entry entry = authorizations.get(uri);
            if (entry == null) {
                throw new IllegalArgumentException("unknown upload authorization");
            }
            ContentSha256 checksum = checksum(content);
            if (content.length != entry.constraints.sizeBytes()
                    || !checksum.equals(entry.constraints.checksum())) {
                throw new IllegalArgumentException("uploaded content did not match authorization");
            }
            entry.content = content.clone();
        }

        void pauseNextInspection() {
            inspectionStarted = new CountDownLatch(1);
            inspectionRelease = new CountDownLatch(1);
        }

        boolean awaitInspection() throws InterruptedException {
            CountDownLatch started = inspectionStarted;
            return started != null && started.await(5, TimeUnit.SECONDS);
        }

        void resumeInspection() {
            CountDownLatch release = inspectionRelease;
            if (release != null) {
                release.countDown();
            }
        }

        @Override
        public ObjectMetadata inspect(ObjectKey key) {
            Entry entry = requireEntry(key);
            awaitInspectionRelease();
            return new ObjectMetadata(
                    entry.content.length,
                    entry.constraints.mediaType(),
                    checksum(entry.content)
            );
        }

        @Override
        public ObjectContent open(ObjectKey key) {
            Entry entry = requireEntry(key);
            byte[] content = entry.content.clone();
            ObjectMetadata metadata = inspect(key);
            return new ObjectContent() {
                private final ByteArrayInputStream input = new ByteArrayInputStream(content);

                @Override
                public ObjectMetadata metadata() {
                    return metadata;
                }

                @Override
                public ByteArrayInputStream inputStream() {
                    return input;
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public void delete(ObjectKey key) {
            objects.remove(key);
        }

        private void awaitInspectionRelease() {
            CountDownLatch started = inspectionStarted;
            CountDownLatch release = inspectionRelease;
            if (started == null || release == null) {
                return;
            }
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release object inspection");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while coordinating object inspection", exception);
            } finally {
                if (inspectionStarted == started) {
                    inspectionStarted = null;
                    inspectionRelease = null;
                }
            }
        }

        private Entry requireEntry(ObjectKey key) {
            Entry entry = objects.get(key);
            if (entry == null || entry.content == null) {
                throw new ObjectStorageException(ObjectStorageFailureCode.NOT_FOUND, false, null);
            }
            return entry;
        }

        private static final class Entry {
            private final UploadConstraints constraints;
            private volatile byte[] content;

            private Entry(UploadConstraints constraints) {
                this.constraints = constraints;
            }
        }
    }

    private static final class CoordinatedSourceRepository extends JdbcSourceRepository {

        private final CountDownLatch lockReached = new CountDownLatch(1);
        private final CountDownLatch allowLock = new CountDownLatch(1);

        private CoordinatedSourceRepository(JdbcClient jdbcClient) {
            super(jdbcClient);
        }

        @Override
        public SourcePair lockAuthorized(
                TenantId tenantId,
                ActorId actorId,
                SourceId sourceId,
                boolean globalAccess
        ) {
            lockReached.countDown();
            try {
                if (!allowLock.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release the source lock");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while coordinating the source lock", exception);
            }
            return super.lockAuthorized(tenantId, actorId, sourceId, globalAccess);
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
