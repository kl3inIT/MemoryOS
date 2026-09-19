package io.memoryos.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import io.memoryos.TestDatabase;
import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourcePermissions;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemView;
import io.memoryos.connector.SourceManagementService;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceStatus;
import io.memoryos.connector.SourceUploadReceipt;
import io.memoryos.connector.persistence.JdbcCleanupAttemptRepository;
import io.memoryos.connector.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceGroupRepository;
import io.memoryos.connector.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.persistence.JdbcSourceOperationQueryRepository;
import io.memoryos.connector.persistence.JdbcSourceQueryRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.connector.persistence.JdbcSourceUploadRepository;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.DocumentId;
import io.memoryos.document.persistence.JdbcDocumentRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.group.GroupId;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.IamException;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.iam.group.DefaultGroupScopeService;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.persistence.GroupInvariantRepository;
import io.memoryos.iam.group.persistence.GroupProjectionRepository;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.ingestion.OperationDelivery;
import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.ingestion.persistence.JdbcOperationDispatchRepository;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectRangeContent;
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
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresSourceLifecycleTest {
    private HikariDataSource dataSource;

    @AfterEach
    void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }


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
        seedGroups(actorId);
        owner = new ActorId(actorId);

        var sourceRepository = new JdbcSourceRepository(jdbcClient, event -> { });
        var sourceDocuments = new JdbcSourceDocumentRepository(jdbcClient);
        attempts = new JdbcIndexAttemptRepository(jdbcClient, sourceRepository, sourceDocuments,
                org.mockito.Mockito.mock(io.memoryos.connector.ProviderAuthorityService.class));
        var documents = new JdbcDocumentRepository(jdbcClient, objectMapper, _ -> { });
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
                        storedObjects,
                        new JdbcSourceItemRepository(jdbcClient),
                        org.mockito.Mockito.mock(io.memoryos.objectstorage.ObjectWriteService.class)
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
            var first = executor.submit(() -> service.createFileSource(owner, "First", List.of(), null));
            var second = executor.submit(() -> service.createFileSource(owner, "Second", List.of(), null));
            first.get();
            second.get();
        }
        assertEquals(1L, count("credentials"));
        assertEquals(2L, count("connectors"));
        assertEquals(2L, count("connector_credential_pairs"));
    }

    @Test
    void itemPagesUseStableDescendingKeysWhileNewUploadsArrive() {
        SourceId sourceId = service.createFileSource(owner, "Paged files", List.of(), null).id();
        var empty = service.listItems(owner, sourceId, null, 2);
        assertTrue(empty.items().isEmpty());
        assertNull(empty.nextCursor());
        assertEquals(0L, empty.totalItems());
        var uploaded = new ArrayList<SourceItemView>();
        for (int index = 0; index < 4; index++) {
            var item = upload(owner, sourceId, "item-" + index + ".txt",
                    ("page content " + index).getBytes(StandardCharsets.UTF_8)).item();
            uploaded.add(item);
            jdbcClient.sql("UPDATE connector_items SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00.123456+00' WHERE id = :id")
                    .param("id", item.id().value()).update();
        }
        jdbcClient.sql("UPDATE connector_items SET status = 'FAILED' WHERE id = :id")
                .param("id", uploaded.getFirst().id().value()).update();
        SourceId other = service.createFileSource(owner, "Other files", List.of(), null).id();
        upload(owner, other, "other.txt", "other source content".getBytes(StandardCharsets.UTF_8));
        assertEquals(1L, service.listItems(owner, other, null, 2).totalItems());
        assertEquals(0L, new JdbcSourceQueryRepository(jdbcClient)
                .items(new TenantId(UUID.randomUUID()), sourceId, null, 2).totalItems());
        var expected = uploaded.stream()
                .sorted((left, right) -> right.id().value().toString().compareTo(left.id().value().toString()))
                .map(SourceItemView::id).toList();
        var complete = service.listItems(owner, sourceId, null, 4);
        assertEquals(expected, complete.items().stream().map(SourceItemView::id).toList());
        assertNull(complete.nextCursor());
        assertEquals(4L, complete.totalItems());

        var first = service.listItems(owner, sourceId, null, 2);
        assertEquals(expected.subList(0, 2), first.items().stream().map(SourceItemView::id).toList());
        assertNotNull(first.nextCursor());
        assertEquals(4L, first.totalItems());
        var newest = upload(owner, sourceId, "new.txt", "new page content".getBytes(StandardCharsets.UTF_8)).item();
        var second = service.listItems(owner, sourceId, first.nextCursor(), 2);
        assertEquals(expected.subList(2, 4), second.items().stream().map(SourceItemView::id).toList());
        assertNull(second.nextCursor());
        assertEquals(5L, second.totalItems());
        assertEquals(newest.id(), service.listItems(owner, sourceId, null, 1).items().getFirst().id());
        jdbcClient.sql("UPDATE connector_items SET created_at = CURRENT_TIMESTAMP WHERE id IN (:first, :second)")
                .param("first", expected.get(2).value()).param("second", expected.get(3).value()).update();
        var exhausted = service.listItems(owner, sourceId, first.nextCursor(), 2);
        assertTrue(exhausted.items().isEmpty());
        assertEquals(5L, exhausted.totalItems());
    }

    @Test
    void itemCursorsRejectOtherSourcesTenantsKindsAndMalformedPositionsAfterAuthorityChecks() {
        SourceId sourceId = service.createFileSource(owner, "Cursor scope", List.of(), null).id();
        for (int index = 0; index < 2; index++) {
            upload(owner, sourceId, "cursor-" + index + ".txt",
                    ("cursor content " + index).getBytes(StandardCharsets.UTF_8));
        }
        var page = service.listItems(owner, sourceId, null, 1);
        assertNotNull(page.nextCursor());
        SourceId other = service.createFileSource(owner, "Other cursor scope", List.of(), null).id();
        assertEquals("SOURCE_INVALID_REQUEST", assertThrows(SourceException.class,
                () -> service.listItems(owner, other, page.nextCursor(), 1)).code());
        String scope = tenantId + "|" + sourceId.value() + "|ITEM|";
        for (String position : List.of(
                UUID.randomUUID() + "|" + sourceId.value() + "|ITEM|2026-01-01T00:00:00Z|" + UUID.randomUUID(),
                tenantId + "|" + sourceId.value() + "|INDEX|1",
                scope + "invalid-date|" + UUID.randomUUID(),
                scope + "+294277-01-01T00:00:00Z|" + UUID.randomUUID(),
                scope + Instant.MAX + "|" + UUID.randomUUID(),
                scope + "2026-01-01T00:00:00Z|invalid-id")) {
            String cursor = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(position.getBytes(StandardCharsets.UTF_8));
            assertEquals("SOURCE_INVALID_REQUEST", assertThrows(SourceException.class,
                    () -> service.listItems(owner, sourceId, cursor, 1)).code());
        }
        assertEquals("SOURCE_INVALID_REQUEST", assertThrows(SourceException.class,
                () -> service.listItems(owner, sourceId, "!", 1)).code());
        assertEquals("SOURCE_INVALID_REQUEST", assertThrows(SourceException.class,
                () -> service.listItems(owner, sourceId, null, 0)).code());
        assertEquals("SOURCE_INVALID_REQUEST", assertThrows(SourceException.class,
                () -> service.listItems(owner, sourceId, null, 101)).code());
        assertEquals("SOURCE_NOT_FOUND", assertThrows(SourceException.class,
                () -> service.listItems(owner, new SourceId(UUID.randomUUID()), "!", 1)).code());
        jdbcClient.sql("DELETE FROM iam_group_memberships WHERE actor_id = :actor")
                .param("actor", owner.value()).update();
        assertEquals("IAM_ACCESS_DENIED", assertThrows(IamException.class,
                () -> service.listItems(owner, sourceId, page.nextCursor(), 1)).code());
    }

    @Test
    void globalSourceCreationNeedsNoAssociationsAndRejectsUnknownGroupsAtomically() {
        var defaultSource = service.createFileSource(owner, "Unassociated source", List.of(), null);
        assertTrue(service.listSourceGroups(owner, defaultSource.id()).isEmpty());

        long connectorCount = count("connectors");
        IamException failure = assertThrows(
                IamException.class,
                () -> service.createFileSource(owner, "Invalid source", List.of(new GroupId(UUID.randomUUID())), null)
        );
        assertEquals("IAM_GROUP_NOT_FOUND", failure.code());
        assertEquals(connectorCount, count("connectors"));
        assertEquals(connectorCount, count("connector_credential_pairs"));
        assertEquals(0L, count("source_group_grants"));
    }

    @Test
    void sourcesWithoutARecordedManagerStayReadOnlyUntilAnAdministratorAppointsOne() {
        GroupId a = new GroupId(UUID.randomUUID());
        ActorId manager = addScopedManager(a);
        GroupId b = new GroupId(UUID.randomUUID());
        addScopedManager(b);
        var publicSource = service.createFileSource(owner, "Public", List.of(a), null);
        var shared = service.createFileSource(owner, "Shared", List.of(a, b), SourceAccess.PRIVATE);
        jdbcClient.sql("""
                INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id, is_manager)
                VALUES (:tenant, :group, :actor, FALSE)
                """).param("tenant", tenantId).param("group", b.value()).param("actor", manager.value()).update();
        var memberOnly = service.createFileSource(owner, "Member only", List.of(b), SourceAccess.PRIVATE);
        var managedOnly = service.createFileSource(owner, "Managed only", List.of(a), SourceAccess.PRIVATE);

        // Managing every associated Group is no longer Source authority: these Sources record no manager.
        for (var source : List.of(publicSource, shared, memberOnly, managedOnly)) {
            assertNull(service.getSource(owner, source.id()).managerActorId());
            assertEquals(SourcePermissions.NONE, service.getSource(manager, source.id()).permissions());
            assertThrows(SourceException.class, () -> service.renameSource(manager, source.id(), "Denied"));
            assertThrows(SourceException.class, () -> upload(manager, source.id(), "denied.txt", new byte[] {1}));
            assertThrows(SourceException.class, () -> service.replaceSourceGroups(manager, source.id(), List.of(a)));
            assertThrows(SourceException.class, () -> service.deleteSource(manager, source.id()));
        }
        assertEquals("Global rename", service.renameSource(owner, shared.id(), "Global rename").name());

        assertThrows(IamException.class, () -> service.assignSourceManager(manager, shared.id(), manager));
        assertEquals("SOURCE_MANAGER_NOT_ELIGIBLE", assertThrows(SourceException.class,
                () -> service.assignSourceManager(owner, shared.id(), owner)).code());
        assertEquals(manager, service.assignSourceManager(owner, shared.id(), manager).managerActorId());
        assertEquals("Managed rename", service.renameSource(manager, shared.id(), "  Managed rename  ").name());

        // Group b answers to its own manager, so the appointed manager may neither drop nor duplicate it.
        assertThrows(IamException.class, () -> service.replaceSourceGroups(manager, shared.id(), List.of(a)));
        assertThrows(IamException.class, () -> service.replaceSourceGroups(manager, shared.id(), List.of(adminGroupId(), b)));
        service.replaceSourceGroups(manager, shared.id(), List.of(b));
        assertThat(service.listSourceGroups(owner, shared.id())).extracting(io.memoryos.iam.group.GroupIdentity::id).containsExactly(b);
        assertTrue(service.getSource(manager, shared.id()).permissions().edit());
        assertThrows(IamException.class, () -> service.updateSourceAccess(manager, shared.id(), SourceAccess.PUBLIC));

        // A public Source stays read-only even for its recorded manager, and clearing the manager restores that.
        service.assignSourceManager(owner, publicSource.id(), manager);
        assertEquals(SourcePermissions.NONE, service.getSource(manager, publicSource.id()).permissions());
        long before = jdbcClient.sql("SELECT authorization_version FROM tenants WHERE id=:tenant")
                .param("tenant", tenantId).query(Long.class).single();
        service.updateSourceAccess(owner, publicSource.id(), SourceAccess.PRIVATE);
        assertThat(jdbcClient.sql("SELECT authorization_version FROM tenants WHERE id=:tenant")
                .param("tenant", tenantId).query(Long.class).single()).isGreaterThan(before);
        assertTrue(service.getSource(manager, publicSource.id()).permissions().edit());
        assertNull(service.assignSourceManager(owner, publicSource.id(), null).managerActorId());
        service.updateSourceAccess(owner, publicSource.id(), SourceAccess.PUBLIC);
        assertEquals(SourcePermissions.NONE, service.getSource(manager, publicSource.id()).permissions());
        assertThrows(SourceException.class, () -> service.updateSourceAccess(owner, publicSource.id(), SourceAccess.SYNC));
        assertEquals(SourceAccess.PUBLIC, service.getSource(owner, publicSource.id()).access());
        jdbcClient.sql("""
                UPDATE connectors SET connector_type='GOOGLE_DRIVE'
                WHERE id=(SELECT connector_id FROM connector_credential_pairs WHERE id=:source)
                """).param("source", shared.id().value()).update();
        assertThrows(IamException.class, () -> service.updateSourceAccess(manager, shared.id(), SourceAccess.SYNC));
        for (var mode : List.of(SourceAccess.SYNC, SourceAccess.PUBLIC, SourceAccess.PRIVATE)) {
            service.updateSourceAccess(owner, shared.id(), mode);
            assertEquals(mode.name(), jdbcClient.sql("SELECT access_type FROM connector_credential_pairs WHERE id=:source")
                    .param("source", shared.id().value()).query(String.class).single());
        }
    }

    @Test
    void scopedCreationIsRestrictedAndMayStartWithoutAGroup() {
        GroupId managed = new GroupId(UUID.randomUUID());
        ActorId manager = addScopedManager(managed);
        GroupId foreign = new GroupId(UUID.randomUUID());
        addScopedManager(foreign);
        assertThrows(SourceException.class, () -> service.createFileSource(manager, "Public", List.of(managed), SourceAccess.PUBLIC));
        assertThrows(IamException.class, () -> service.createFileSource(manager, "Foreign", List.of(foreign), null));
        assertThrows(IamException.class, () -> service.createFileSource(manager, "Mixed", List.of(managed, foreign), null));
        assertThrows(IamException.class, () -> service.createFileSource(manager, "System", List.of(adminGroupId()), null));
        assertEquals(0L, count("connector_credential_pairs"));

        // Nobody reads a private Source with no Group; its creator attaches it when they are ready.
        var source = service.createFileSource(manager, "Unattached", List.of(), null);
        assertEquals(SourceAccess.PRIVATE, source.access());
        assertEquals(manager, source.managerActorId());
        assertThat(service.listSourceGroups(manager, source.id())).isEmpty();
        assertEquals("Renamed", service.renameSource(manager, source.id(), "Renamed").name());
        service.replaceSourceGroups(manager, source.id(), List.of(managed));
        assertThat(service.listSourceGroups(manager, source.id()))
                .extracting(io.memoryos.iam.group.GroupIdentity::id).containsExactly(managed);
        assertThrows(SourceException.class, () -> service.deleteSource(manager, source.id()));
        service.replaceSourceGroups(manager, source.id(), List.of());
        assertThat(service.listSourceGroups(manager, source.id())).isEmpty();
    }

    @Test
    void creatorCanDeleteOnlyOwnNonpublicGrouplessSourceAndPollAfterCleanup() {
        GroupId managed = new GroupId(UUID.randomUUID());
        ActorId manager = addScopedManager(managed);
        GroupId other = new GroupId(UUID.randomUUID());
        ActorId stranger = addScopedManager(other);
        var own = service.createFileSource(manager, "Own", List.of(managed), null);
        var foreign = service.createFileSource(owner, "Foreign", List.of(managed), SourceAccess.PRIVATE);
        jdbcClient.sql("DELETE FROM source_group_grants WHERE tenant_id=:tenant")
                .param("tenant", tenantId).update();
        assertEquals(new SourcePermissions(true, true, false, false, false), service.getSource(manager, own.id()).permissions());
        assertThrows(SourceException.class, () -> service.getSource(manager, foreign.id()));
        assertThrows(SourceException.class, () -> service.deleteSource(manager, foreign.id()));
        service.updateSourceAccess(owner, own.id(), SourceAccess.PUBLIC);
        assertThrows(SourceException.class, () -> service.deleteSource(manager, own.id()));
        service.updateSourceAccess(owner, own.id(), SourceAccess.PRIVATE);
        var deletion = service.deleteSource(manager, own.id());
        assertEquals(deletion, service.deleteSource(manager, own.id()));
        var delivery = dispatch(OperationWorkload.CLEANUP);
        var work = cleanup.claim(delivery.tenantId(), delivery.operationId(), delivery.deliveryId()).orElseThrow();
        assertTrue(cleanup.execute(work));
        assertThrows(SourceException.class, () -> service.getSource(manager, own.id()));
        assertEquals(io.memoryos.connector.SourceOperationStatus.SUCCEEDED, service.getOperation(manager, deletion.id()).status());
        assertEquals(deletion.id(), service.deleteSource(manager, own.id()).id());
        assertThrows(SourceException.class, () -> service.getOperation(stranger, deletion.id()));
        jdbcClient.sql("UPDATE iam_group_memberships SET is_manager=FALSE WHERE tenant_id=:tenant AND actor_id=:actor")
                .param("tenant", tenantId).param("actor", manager.value()).update();
        assertThrows(IamException.class, () -> service.getOperation(manager, deletion.id()));
        assertThrows(IamException.class, () -> service.deleteSource(manager, own.id()));
    }

    @Test
    void pendingSelectionReceiptBelongsToItsActorAndStillRequiresCurrentRole() {
        GroupId group = new GroupId(UUID.randomUUID());
        ActorId manager = addScopedManager(group);
        ActorId stranger = addScopedManager(new GroupId(UUID.randomUUID()));
        var operation = new io.memoryos.connector.SourceOperationId(UUID.randomUUID());
        jdbcClient.sql("""
                INSERT INTO google_drive_selection_operations (
                    id, tenant_id, source_id, actor_id, request_id, request_hash,
                    credential_revision, scope_revision, discovery_revision, scope_mode,
                    max_requests, max_metadata, max_roots, max_request_bytes)
                VALUES (:operation, :tenant, :source, :actor, :request, 'hash',
                    1, 0, 0, 'SPECIFIC', 100, 100, 100, 10000)
                """).param("operation", operation.value()).param("tenant", tenantId)
                .param("source", UUID.randomUUID()).param("actor", manager.value())
                .param("request", UUID.randomUUID()).update();
        assertEquals(SourceOperationType.VALIDATE_GOOGLE_DRIVE_SELECTION, service.getOperation(manager, operation).type());
        assertThrows(SourceException.class, () -> service.getOperation(stranger, operation));
        jdbcClient.sql("UPDATE iam_group_memberships SET is_manager=FALSE WHERE tenant_id=:tenant AND actor_id=:actor")
                .param("tenant", tenantId).param("actor", manager.value()).update();
        assertThrows(IamException.class, () -> service.getOperation(manager, operation));
        assertEquals(operation, service.getOperation(owner, operation).id());
    }

    @Test
    void scopedManagerReadsAndManagesOnlyAssociatedSources() {
        GroupId managedGroupId = new GroupId(UUID.randomUUID());
        ActorId manager = addScopedManager(managedGroupId);
        GroupId foreignGroupId = new GroupId(UUID.randomUUID());
        addScopedManager(foreignGroupId);
        var managed = service.createFileSource(manager, "Managed source", List.of(managedGroupId), SourceAccess.PRIVATE);
        var hidden = service.createFileSource(owner, "Hidden source", List.of(), SourceAccess.PRIVATE);
        var managedUpload = upload(
                manager,
                managed.id(),
                "managed.txt",
                "managed content".getBytes(StandardCharsets.UTF_8)
        );
        var hiddenUpload = upload(
                owner,
                hidden.id(),
                "hidden.txt",
                "hidden content".getBytes(StandardCharsets.UTF_8)
        );

        var visible = service.listSources(manager);
        assertEquals(1, visible.size());
        assertEquals(managed.id(), visible.getFirst().id());
        assertEquals(new SourcePermissions(true, false, false, false, false), visible.getFirst().permissions());
        assertThrows(SourceException.class, () -> service.getSource(manager, hidden.id()));
        assertEquals(
                managedUpload.operation(),
                service.getOperation(manager, managedUpload.operation().id())
        );
        assertThrows(
                SourceException.class,
                () -> service.getOperation(manager, hiddenUpload.operation().id())
        );
        service.reindex(manager, managed.id(), managedUpload.item().id());
        IamException deleteDenied = assertThrows(
                IamException.class,
                () -> service.removeItem(manager, managed.id(), managedUpload.item().id())
        );
        assertEquals("IAM_ACCESS_DENIED", deleteDenied.code());
        assertThrows(
                SourceException.class,
                () -> service.deleteSource(manager, managed.id())
        );
        service.replaceSourceGroups(manager, managed.id(), List.of(managedGroupId));
        assertThat(service.listSourceGroupOptions(manager, "", 0, 25).items())
                .extracting(io.memoryos.iam.group.GroupIdentity::id).containsExactly(managedGroupId);

        // Moving the Source to another manager's Group leaves its recorded manager in place: they keep the catalog
        // row and their operations, while reading its documents still needs membership they no longer have.
        service.replaceSourceGroups(
                owner,
                managed.id(),
                List.of(foreignGroupId)
        );
        assertThat(service.listSources(manager)).extracting(io.memoryos.connector.SourceSummary::id)
                .containsExactly(managed.id());
        assertTrue(service.getSource(manager, managed.id()).permissions().edit());

        service.assignSourceManager(owner, managed.id(), null);
        assertTrue(service.listSources(manager).isEmpty());
        assertThrows(SourceException.class, () -> service.getSource(manager, managed.id()));
        assertThrows(
                SourceException.class,
                () -> service.getOperation(manager, managedUpload.operation().id())
        );
    }

    @Test
    void groupManagersDetachSourcesFromTheirOwnGroupWithoutSourceAuthority() {
        GroupId managedGroup = new GroupId(UUID.randomUUID());
        ActorId manager = addScopedManager(managedGroup);
        GroupId foreignGroup = new GroupId(UUID.randomUUID());
        addScopedManager(foreignGroup);
        var shared = service.createFileSource(owner, "Shared", List.of(managedGroup, foreignGroup), SourceAccess.PRIVATE);
        var onlyGroup = service.createFileSource(owner, "Only group", List.of(managedGroup), SourceAccess.PRIVATE);
        var publicShared = service.createFileSource(owner, "Public shared", List.of(managedGroup), SourceAccess.PUBLIC);
        var foreignOnly = service.createFileSource(owner, "Foreign only", List.of(foreignGroup), SourceAccess.PRIVATE);

        // What their own Group carries is theirs to decide, even for Sources they cannot otherwise manage.
        assertEquals(SourcePermissions.NONE, service.getSource(manager, shared.id()).permissions());
        assertThat(service.listGroupSources(manager, managedGroup).removableSourceIds())
                .containsExactlyInAnyOrder(shared.id(), onlyGroup.id(), publicShared.id());
        assertThrows(IamException.class, () -> service.removeGroupSource(manager, foreignGroup, shared.id()));
        assertEquals("SOURCE_NOT_FOUND", assertThrows(SourceException.class,
                () -> service.removeGroupSource(manager, managedGroup, foreignOnly.id())).code());

        service.removeGroupSource(manager, managedGroup, shared.id());
        assertThat(service.listSourceGroups(owner, shared.id()))
                .extracting(io.memoryos.iam.group.GroupIdentity::id).containsExactly(foreignGroup);
        service.removeGroupSource(manager, managedGroup, onlyGroup.id());
        assertThat(service.listSourceGroups(owner, onlyGroup.id())).isEmpty();
        service.removeGroupSource(manager, managedGroup, publicShared.id());
        assertThat(service.listGroupSources(manager, managedGroup).sources()).isEmpty();
        assertEquals("SOURCE_NOT_FOUND", assertThrows(SourceException.class,
                () -> service.removeGroupSource(manager, managedGroup, shared.id())).code());

        service.removeGroupSource(owner, foreignGroup, shared.id());
        assertThat(service.listSourceGroups(owner, shared.id())).isEmpty();
    }

    @Test
    void ordinaryMemberWithGlobalSourceManagementCanRemoveItemsAndDeleteUnassociatedSources() {
        GroupId grantGroup = new GroupId(UUID.randomUUID());
        ActorId member = addScopedManager(grantGroup);
        jdbcClient.sql("""
                        UPDATE iam_group_memberships SET is_manager = FALSE
                        WHERE tenant_id = :tenantId AND group_id = :groupId AND actor_id = :actorId
                        """)
                .param("tenantId", tenantId).param("groupId", grantGroup.value())
                .param("actorId", member.value()).update();
        jdbcClient.sql("""
                        INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                        VALUES (:tenantId, :groupId, 'SOURCES_MANAGE')
                        """)
                .param("tenantId", tenantId).param("groupId", grantGroup.value()).update();
        var authorization = new DefaultIamAuthorization(
                new IamAuthorizationRepository(jdbcClient), new IamLockRepository(jdbcClient)
        );
        assertEquals(Set.of(IamCapability.SOURCES_MANAGE, IamCapability.SOURCES_READ,
                IamCapability.SOURCES_DELETE), authorization.effectiveCapabilities(member));
        assertEquals(Set.of(), authorization.scopedCapabilities(member));

        SourceId sourceId = service.createFileSource(owner, "Unassociated source", List.of(), null).id();
        var uploaded = upload(member, sourceId, "remove.txt", "remove me".getBytes(StandardCharsets.UTF_8));
        var source = service.getSource(member, sourceId);
        assertEquals(new SourcePermissions(true, true, true, true, true), source.permissions());

        var removal = service.removeItem(member, sourceId, uploaded.item().id());
        assertEquals(SourceOperationType.REMOVE_ITEM, removal.type());
        assertEquals(removal, service.getOperation(member, removal.id()));
        assertEquals("DELETING", jdbcClient.sql("""
                        SELECT status FROM connector_items WHERE tenant_id = :tenantId AND id = :itemId
                        """)
                .param("tenantId", tenantId).param("itemId", uploaded.item().id().value())
                .query(String.class).single());

        var deletion = service.deleteSource(member, sourceId);
        assertEquals(SourceOperationType.DELETE_SOURCE, deletion.type());
        assertEquals(deletion, service.getOperation(member, deletion.id()));
        assertEquals("DELETING", jdbcClient.sql("""
                        SELECT status FROM connector_credential_pairs WHERE tenant_id = :tenantId AND id = :sourceId
                        """)
                .param("tenantId", tenantId).param("sourceId", sourceId.value())
                .query(String.class).single());
    }

    @Test
    void managerRevocationDuringProviderVerificationPreventsUploadCommit() throws Exception {
        GroupId managedGroupId = new GroupId(UUID.randomUUID());
        ActorId manager = addScopedManager(managedGroupId);
        SourceId sourceId = service.createFileSource(manager, "Revoked source", List.of(managedGroupId), SourceAccess.PRIVATE).id();
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
            service.assignSourceManager(owner, sourceId, null);
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
        SourceId sourceId = service.createFileSource(owner, "Files", List.of(), null).id();
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
        SourceId sourceId = service.createFileSource(owner, "Lost response", List.of(), null).id();
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
        SourceId sourceId = service.createFileSource(owner, "Duplicate cleanup", List.of(), null).id();
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
        SourceId sourceId = service.createFileSource(owner, "Relay", List.of(), null).id();
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
        SourceId sourceId = service.createFileSource(owner, "Transport", List.of(), null).id();
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
        SourceId sourceId = service.createFileSource(owner, "Retry", List.of(), null).id();
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
                assertThat(work.initialQueueWait()).isNotNull().isGreaterThanOrEqualTo(Duration.ZERO);
            } else {
                assertNull(work.initialQueueWait());
            }
            assertTrue(attempts.retry(
                    work,
                    "SOURCE_EXTRACTION_INTERNAL",
                    null,
                    null,
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
        SourceId sourceId = service.createFileSource(owner, "Lease", List.of(), null).id();
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
        assertThat(stale.initialQueueWait()).isNotNull().isGreaterThanOrEqualTo(Duration.ZERO);
        assertNull(current.initialQueueWait());
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
    void successfulReindexClearsOnlyRecoveredItemFailures() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Recovery", List.of(), null).id();
        var uploads = new java.util.ArrayList<SourceUploadReceipt>();
        String[] failures = {"SOURCE_EXTRACTION_MALFORMED", "SOURCE_EXTRACTION_TIMEOUT"};
        for (int index = 0; index < failures.length; index++) {
            uploads.add(upload(owner, sourceId, "item-" + index + ".txt",
                    ("content " + index).getBytes(StandardCharsets.UTF_8)));
            var delivery = dispatch(OperationWorkload.INGESTION);
            var work = attempts.claim(delivery.tenantId(), delivery.operationId(), delivery.deliveryId()).orElseThrow();
            assertTrue(attempts.fail(work, failures[index], null, null));
        }
        assertEquals(failures[1], service.getSource(owner, sourceId).errorCode());

        for (int index = uploads.size() - 1; index >= 0; index--) {
            var itemId = uploads.get(index).item().id();
            var reindex = service.reindex(owner, sourceId, itemId);
            var item = service.listItems(owner, sourceId, null, 25).items().stream()
                    .filter(current -> current.id().equals(itemId)).findFirst().orElseThrow();
            assertEquals(reindex.id(), item.latestAttempt().id());
            assertNull(item.lastIndexedAt());
            assertNull(item.latestAttempt().startedAt());
            assertNull(item.errorCode());
            var delivery = dispatch(OperationWorkload.INGESTION);
            var work = attempts.claim(delivery.tenantId(), delivery.operationId(), delivery.deliveryId()).orElseThrow();
            DocumentId documentId = new DocumentId(UUID.randomUUID());
            jdbcClient.sql("""
                            INSERT INTO documents (id, tenant_id, status)
                            VALUES (:id, :tenantId, 'ELIGIBLE')
                            """)
                    .param("id", documentId.value())
                    .param("tenantId", tenantId)
                    .update();
            assertTrue(attempts.complete(work, documentId));
            var indexed = service.listItems(owner, sourceId, null, 25).items().stream()
                    .filter(current -> current.id().equals(itemId)).findFirst().orElseThrow();
            assertEquals(service.getOperation(owner, reindex.id()).completedAt(), indexed.lastIndexedAt());
            assertNotNull(indexed.latestAttempt().startedAt());
            var historyAttempt = service.listIndexAttempts(owner, sourceId, null, 25).items().stream()
                    .filter(current -> current.id().equals(reindex.id())).findFirst().orElseThrow();
            assertEquals("item-" + index + ".txt", historyAttempt.filename());
            assertEquals(indexed.lastIndexedAt(), historyAttempt.completedAt());
            var source = service.getSource(owner, sourceId);
            assertEquals(index == 1 ? failures[0] : null, source.errorCode());
            assertEquals(uploads.size() - index, source.documentCount());
        }
        var previouslyIndexed = service.listItems(owner, sourceId, null, 25).items().getFirst();
        var pending = service.reindex(owner, sourceId, previouslyIndexed.id());
        var reindexing = service.listItems(owner, sourceId, null, 25).items().stream()
                .filter(current -> current.id().equals(previouslyIndexed.id())).findFirst().orElseThrow();
        assertEquals(previouslyIndexed.lastIndexedAt(), reindexing.lastIndexedAt());
        assertEquals(pending.id(), reindexing.latestAttempt().id());
        assertNull(reindexing.latestAttempt().completedAt());
    }

    @Test
    void uploadRejectsAnItemWhoseRemovalIsPending() {
        SourceId sourceId = service.createFileSource(owner, "Deleting item", List.of(), null).id();
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
        var documents = new JdbcDocumentRepository(jdbcClient, objectMapper, _ -> { });
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
        SourceId sourceId = service.createFileSource(owner, "Cleanup race", List.of(), null).id();
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
        SourceId sourceId = service.createFileSource(owner, "Single flight", List.of(), null).id();
        byte[] content = "single flight".getBytes(StandardCharsets.UTF_8);
        var upload = upload(owner, sourceId, "single.txt", content);
        OperationDelivery initialDelivery = dispatch(OperationWorkload.INGESTION);
        var initialWork = attempts.claim(
                initialDelivery.tenantId(),
                initialDelivery.operationId(),
                initialDelivery.deliveryId()
        ).orElseThrow();
        assertTrue(attempts.fail(initialWork, "SOURCE_EXTRACTION_TIMEOUT", null, null));

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
        assertThat(staleCleanup.initialQueueWait()).isNotNull().isGreaterThanOrEqualTo(Duration.ZERO);
        assertNull(currentCleanup.initialQueueWait());
        assertNotEquals(staleCleanup.claimToken(), currentCleanup.claimToken());
        assertFalse(cleanup.fail(staleCleanup, "SOURCE_CLEANUP_INTERNAL", null, null));
        assertTrue(cleanup.fail(currentCleanup, "SOURCE_CLEANUP_INTERNAL", null, null));
    }

    @Test
    void inactiveTenantCancelsPendingIndexWorkWithoutPublishing() {
        SourceId sourceId = service.createFileSource(owner, "Inactive", List.of(), null).id();
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

    @Test
    void pauseBlocksNewWorkAndResumeRequeuesCanceledIndexing() {
        SourceId sourceId = service.createFileSource(owner, "Pausable", List.of(), null).id();
        upload(owner, sourceId, "one.txt", "one".getBytes(StandardCharsets.UTF_8));
        upload(owner, sourceId, "two.txt", "two".getBytes(StandardCharsets.UTF_8));

        // Pause: summary reports PAUSED and queued attempts are canceled with SOURCE_PAUSED.
        var paused = service.pauseSource(owner, sourceId);
        assertEquals(SourceStatus.PAUSED, paused.status());
        assertEquals(
                2,
                jdbcClient.sql("SELECT COUNT(*) FROM index_attempts WHERE status = 'CANCELLED' AND error_code = 'SOURCE_PAUSED'")
                        .query(Integer.class).single());

        // While paused, dispatch claims nothing for the source and uploads/reindex are rejected.
        assertTrue(operationDispatch.claim(OperationWorkload.INGESTION, 1).isEmpty());
        assertThrows(SourceException.class,
                () -> service.initiateUpload(owner, sourceId,
                        new ObjectUploadSpecification("three.txt", "text/plain", 5, checksum("three".getBytes(StandardCharsets.UTF_8)))));

        // Resume: canceled attempts are re-enqueued and the source returns to a live status.
        var resumed = service.resumeSource(owner, sourceId);
        assertNotEquals(SourceStatus.PAUSED, resumed.status());
        assertNotEquals(SourceStatus.PAUSING, resumed.status());
        assertEquals(
                2,
                jdbcClient.sql("SELECT COUNT(*) FROM index_attempts WHERE status = 'NOT_STARTED'")
                        .query(Integer.class).single());
        assertFalse(operationDispatch.claim(OperationWorkload.INGESTION, 1).isEmpty());
    }

    @Test
    void pauseIsIdempotentAndResumeWithoutPauseIsANoOp() {
        SourceId sourceId = service.createFileSource(owner, "Idempotent", List.of(), null).id();
        upload(owner, sourceId, "item.txt", "item".getBytes(StandardCharsets.UTF_8));

        // Resume on a non-paused source is a no-op returning the live summary.
        var live = service.resumeSource(owner, sourceId);
        assertNotEquals(SourceStatus.PAUSED, live.status());

        // Pausing twice stays paused and does not double-cancel.
        service.pauseSource(owner, sourceId);
        var paused = service.pauseSource(owner, sourceId);
        assertEquals(SourceStatus.PAUSED, paused.status());
        assertEquals(
                1,
                jdbcClient.sql("SELECT COUNT(*) FROM index_attempts WHERE status = 'CANCELLED' AND error_code = 'SOURCE_PAUSED'")
                        .query(Integer.class).single());
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
                        VALUES (:tenantId, :adminGroupId, 'SYSTEM_ADMIN')
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
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private SourceManagementService service(JdbcSourceRepository sourceRepository) {
        var sourceDocuments = new JdbcSourceDocumentRepository(jdbcClient);
        var target = new DefaultSourceManagementService(
                sourceRepository,
                new JdbcSourceItemRepository(jdbcClient),
                new JdbcIndexAttemptRepository(jdbcClient, sourceRepository, sourceDocuments,
                        org.mockito.Mockito.mock(io.memoryos.connector.ProviderAuthorityService.class)),
                sourceDocuments,
                new JdbcSourceQueryRepository(jdbcClient),
                new JdbcSourceOperationQueryRepository(jdbcClient),
                new JdbcSourceGroupRepository(jdbcClient, event -> { }),
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
                transactionManager,
                new io.memoryos.connector.persistence.JdbcSourceSyncRepository(jdbcClient),
                new io.memoryos.connector.persistence.JdbcGoogleDriveSelectionRepository(jdbcClient),
                org.mockito.Mockito.mock(io.memoryos.connector.GoogleDriveConnectionService.class),
                new SourceAccessPolicy(new DefaultIamAuthorization(new IamAuthorizationRepository(jdbcClient), new IamLockRepository(jdbcClient)), sourceRepository, new DefaultGroupScopeService(new GroupInvariantRepository(jdbcClient), new GroupProjectionRepository(jdbcClient)))
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
        public ObjectRangeContent openRange(ObjectKey key, long first, long last) {
            throw new UnsupportedOperationException("ranged reads are exercised by S3ObjectStorageIntegrationTest");
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
            super(jdbcClient, event -> { });
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
