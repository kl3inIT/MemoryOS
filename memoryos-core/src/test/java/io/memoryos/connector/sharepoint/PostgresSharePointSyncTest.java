package io.memoryos.connector.sharepoint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.ConnectorSyncPort;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointGateway.EntraMember;
import io.memoryos.connector.SharePointGateway.EntraMemberPage;
import io.memoryos.connector.SharePointGateway.Principal;
import io.memoryos.connector.SharePointGateway.PrincipalType;
import io.memoryos.connector.SharePointGateway.RoleAssignment;
import io.memoryos.connector.SharePointSourceService;
import io.memoryos.connector.SourceAclChanged;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.sync.LockingStatementCounter;
import io.memoryos.connector.SharePointGateway;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.connector.SharePointSourceService.Scope;
import io.memoryos.connector.SharePointSourceService.ScopeMode;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceRunTrigger;
import io.memoryos.connector.SourceStatus;
import io.memoryos.connector.sync.SourceSyncAdapterRegistry;
import io.memoryos.connector.sync.SourceSyncAdapters;
import io.memoryos.connector.sync.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointAclRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointCredentialRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointGroupRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSourceRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSourceRepository.ResolvedRoot;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSyncRepository;
import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.source.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.source.persistence.JdbcSourceQueryRepository;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository;
import io.memoryos.connector.sync.SourceSyncEngine;
import io.memoryos.connector.sharepoint.persistence.SharePointCredentialConfiguration;
import io.memoryos.document.DocumentId;
import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.ingestion.persistence.JdbcOperationDispatchRepository;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.application.DefaultObjectWriteService;
import io.memoryos.objectstorage.application.ObjectUploadProperties;
import io.memoryos.objectstorage.persistence.JdbcObjectWriteRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The SharePoint refresh and prune against a real database, with Microsoft and object storage replaced by
 * doubles. What matters here is which documents survive a run, not how Graph is called.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresSharePointSyncTest {
    private static final String DRIVE = "drive-1";
    private static final String SITE = "site-1";

    private HikariDataSource dataSource;
    private LockingStatementCounter locks;
    private JdbcClient jdbc;
    private DataSourceTransactionManager manager;
    private TenantId tenant;
    private ActorId owner;
    private SourceId source;
    private CredentialId credential;
    private SharePointGateway.Session session;
    private JdbcSharePointSyncRepository runs;
    private JdbcSharePointSourceRepository sharePoint;
    private JdbcSourceSyncRepository attempts;
    private JdbcIndexAttemptRepository indexing;
    private SourceSyncAdapterRegistry authority;
    private OperationDispatchPort dispatch;
    private SourceSyncEngine service;
    private TransactionTemplate tx;
    private ObjectStorage storage;
    private Answer<Void> storeObject;
    /** Every permission change the runs published. */
    private final List<SourceAclChanged> aclChanges = new ArrayList<>();

    @AfterEach
    void closeDatabase() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        locks = new LockingStatementCounter(dataSource);
        jdbc = JdbcClient.create(locks.dataSource());
        manager = new DataSourceTransactionManager(locks.dataSource());
        tx = new TransactionTemplate(manager);
        tenant = new TenantId(UUID.randomUUID());
        owner = new ActorId(UUID.randomUUID());
        source = new SourceId(UUID.randomUUID());
        credential = new CredentialId(UUID.randomUUID());
        seedTenant();

        var sources = new JdbcSourceRepository(jdbc, event -> { });
        var documents = new JdbcSourceDocumentRepository(jdbc);
        var items = new JdbcSourceItemRepository(jdbc, _ -> { });
        attempts = new JdbcSourceSyncRepository(jdbc);
        runs = new JdbcSharePointSyncRepository(jdbc);
        sharePoint = new JdbcSharePointSourceRepository(jdbc, sources);
        var credentialRows = new JdbcSharePointCredentialRepository(jdbc, sources,
                new SharePointCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "test"));
        var currentConnections = new SharePointConnectionService(credentialRows, sharePoint,
                mock(SharePointGateway.class), manager);
        authority = SourceSyncAdapters.registry(
                SourceSyncAdapters.credentials(SourceType.SHAREPOINT, currentConnections::current));
        indexing = new JdbcIndexAttemptRepository(jdbc, sources, documents, authority);
        dispatch = TestDatabase.transactionalProxy(new JdbcOperationDispatchRepository(jdbc),
                OperationDispatchPort.class, manager);

        session = mock(SharePointGateway.Session.class);
        when(session.root()).thenReturn(new SharePointGateway.RootSite(SITE, "https://contoso.sharepoint.com",
                "contoso.sharepoint.com"));
        var connections = mock(SharePointConnectionService.class);
        when(connections.state(any(), any())).thenReturn(new SharePointConnectionService.State(credential,
                "Entra app", "ACTIVE", 1L, "contoso.sharepoint.com"));
        when(connections.current(any(), any(), anyLong())).thenReturn(true);
        when(connections.open(any(), any())).thenAnswer(_ ->
                new SharePointConnectionService.Connection(session, 1L, "contoso.sharepoint.com"));

        storage = mock(ObjectStorage.class);
        var storedBytes = new ConcurrentHashMap<ObjectKey, byte[]>();
        var storedMetadata = new ConcurrentHashMap<ObjectKey,
                ObjectMetadata>();
        storeObject = call -> {
            ObjectKey key = call.getArgument(0);
            byte[] value = call.getArgument(1);
            storedBytes.put(key, value);
            storedMetadata.put(key, new ObjectMetadata(value.length, call.getArgument(2),
                    checksum(value)));
            return null;
        };
        doAnswer(storeObject).when(storage).write(any(), any(), any());
        when(storage.inspect(any())).thenAnswer(call -> storedMetadata.get(call.getArgument(0)));
        var writes = new DefaultObjectWriteService(
                new JdbcStoredObjectRepository(jdbc),
                new JdbcObjectWriteRepository(jdbc), storage,
                new ObjectUploadProperties(Duration.ofMinutes(15),
                        Duration.ofSeconds(30), Duration.ofMinutes(5),
                        Duration.ofMinutes(1), 16), manager);
        service = new SourceSyncEngine(attempts, sources, items, indexing, documents, writes,
                SourceSyncAdapters.registry(new SharePointSyncAdapter(runs, sharePoint, attempts, connections,
                        new JdbcSharePointAclRepository(jdbc, event -> aclChanges.add((SourceAclChanged) event)),
                        new SharePointGroupSynchronizer(new JdbcSharePointGroupRepository(jdbc),
                                Duration.ofMinutes(5), false))), manager);
        seedSource();
    }

    @Test
    void currentSharePointVersionCanReplayAndPublish() {
        var file = file("file-current", "Current.pdf", Instant.now());
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(file), null, "delta-link"));
        when(session.item(DRIVE, "file-current")).thenReturn(file);
        when(session.content(any(), eq("contoso.sharepoint.com"), anyInt()))
                .thenReturn(new SharePointGateway.Content("Current.pdf", "application/pdf", "current".getBytes()));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        UUID version = jdbc.sql("""
                SELECT version.id FROM connector_item_versions version
                JOIN connector_items item ON item.tenant_id = version.tenant_id AND item.current_version_id = version.id
                WHERE item.tenant_id = :tenant AND item.provider_file_id = 'file-current'
                """).param("tenant", tenant.value()).query(UUID.class).single();
        assertTrue(Boolean.TRUE.equals(tx.execute(_ -> indexing.canReplay(tenant, source, version))));

        var delivery = dispatch.claim(OperationWorkload.INGESTION, 1).getFirst().delivery();
        SourceOperationId operation = delivery.operationId();
        var work = Objects.requireNonNull(tx.execute(_ ->
                indexing.claim(tenant, operation, delivery.deliveryId()).orElseThrow()));
        DocumentId document = new DocumentId(UUID.randomUUID());
        jdbc.sql("INSERT INTO documents(id,tenant_id,status) VALUES(:id,:tenant,'ELIGIBLE')")
                .param("id", document.value()).param("tenant", tenant.value()).update();

        assertTrue(Boolean.TRUE.equals(tx.execute(_ -> indexing.complete(work, document))));
        assertEquals("SUCCEEDED", jdbc.sql("SELECT status FROM index_attempts WHERE id=:id")
                .param("id", operation.value()).query(String.class).single());
        assertEquals("INDEXED", status("file-current"));
        assertEquals("ACTIVE", jdbc.sql("SELECT status FROM connector_credential_pairs WHERE id=:id")
                .param("id", source.value()).query(String.class).single());
        jdbc.sql("""
                UPDATE credentials SET credential_revision = credential_revision + 1
                WHERE tenant_id = :tenant AND id = :credential
                """).param("tenant", tenant.value()).param("credential", credential.value()).update();
        assertFalse(Boolean.TRUE.equals(tx.execute(_ -> indexing.canReplay(tenant, source, version))));
    }

    @Test
    void refreshStoresChangedContentAndQueuesItForIndexing() {
        var file = file("file-new", "Bao cao.pdf", Instant.now());
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(file), null, "delta-link"));
        when(session.item(DRIVE, "file-new")).thenReturn(file);
        when(session.content(any(), eq("contoso.sharepoint.com"), anyInt()))
                .thenReturn(new SharePointGateway.Content("Bao cao.pdf", "application/pdf",
                        "noi dung bao cao".getBytes(StandardCharsets.UTF_8)));

        var result = service.execute(claim(enqueue()));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, result);
        assertEquals("PENDING", status("file-new"), "the item is held and waiting to be indexed");
        assertEquals(1, counter("acquired"));
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM index_attempts a
                JOIN connector_credential_pairs p ON p.tenant_id = a.tenant_id AND p.id = a.connector_credential_pair_id
                WHERE p.tenant_id = :tenant AND p.id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).query(Integer.class).single(),
                "the acquired version is queued for indexing");

        // A second run over unchanged content neither re-downloads nor queues another attempt.
        service.execute(claim(enqueue()));
        assertEquals(1, counter("acquired"));
        verify(session, times(1)).content(any(), any(), anyInt());
    }

    @Test
    void refreshCollectsSitePagesWhenTheSourceAsksForThem() {
        collectPages();
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(), null, "delta-link"));
        var metadata = new SharePointGateway.SitePageMetadata("page-1", "Trang chủ",
                "https://contoso.sharepoint.com/sites/Finance/SitePages/Home.aspx", "etag-1", Instant.now());
        when(session.pages(eq(SITE), any()))
                .thenReturn(new SharePointGateway.SitePageList(List.of(metadata), null));
        when(session.page(SITE, "page-1")).thenReturn(new SharePointGateway.PageContent(metadata,
                "{\"schema\":\"memoryos-sharepoint-page-v1\"}".getBytes(StandardCharsets.UTF_8)));

        var result = service.execute(claim(enqueue()));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, result);
        assertEquals("PENDING", status("page-1"), "the page is held and waiting to be indexed");
        assertEquals(1, counter("acquired"));
        assertEquals(1, jdbc.sql("""
                SELECT COUNT(*) FROM sharepoint_items
                WHERE tenant_id = :tenant AND source_id = :source AND kind = 'PAGE'
                """).param("tenant", tenant.value()).param("source", source.value()).query(Integer.class).single());

        // Reading it again with the same version neither re-reads the canvas nor queues another attempt.
        service.execute(claim(enqueue()));
        assertEquals(1, counter("acquired"));
        verify(session, times(1)).page(SITE, "page-1");
    }

    @Test
    void pruneRemovesAPageThatIsNoLongerPublished() {
        collectPages();
        seedItem("page-gone", "Trang cũ");
        pruneDue();
        when(session.delta(eq(DRIVE), isNull(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(), null, "delta-link"));
        when(session.pages(eq(SITE), any()))
                .thenReturn(new SharePointGateway.SitePageList(List.of(), null));

        assertEquals(ConnectorSyncPort.Result.CONTINUED, service.execute(claim(enqueue())));

        assertEquals("DELETING", status("page-gone"));
    }

    private void collectPages() {
        jdbc.sql("""
                UPDATE sharepoint_sources SET include_pages = TRUE
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    @Test
    void refreshRemovesWhatATombstoneReports() {
        seedItem("file-gone", "Gone.docx");
        when(session.delta(eq(DRIVE), any(), any())).thenReturn(new SharePointGateway.DeltaPage(
                List.of(tombstone("file-gone")), null, "delta-link"));

        var result = service.execute(claim(enqueue()));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, result);
        assertEquals("DELETING", status("file-gone"), "a tombstone removes the document it names");
        assertEquals(1, counter("removed"));
        assertNotNull(refreshWindowEnd(), "a successful refresh records the window its next run continues from");
    }

    @Test
    void refreshKeepsAnItemWhoseTimestampIsOlderThanTheWindow() {
        // Moving a file into scope does not change its timestamp, so filtering the change log would drop it.
        Instant old = Instant.now().minus(400, ChronoUnit.DAYS);
        var moved = file("file-moved", "Moved.docx", old);
        jdbc.sql("UPDATE sharepoint_sources SET refresh_window_end = :end WHERE tenant_id = :tenant")
                .param("end", Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)))
                .param("tenant", tenant.value()).update();
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(moved), null, "delta-link"));
        // Reading the item back is where acquisition starts; one unreadable item does not fail the run.
        when(session.item(any(), any())).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.NOT_FOUND));

        var result = service.execute(claim(enqueue()));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, result);
        assertEquals(1, ledger("file-moved"), "the change log decides what the refresh handles");
        verify(session).item(DRIVE, "file-moved");
    }

    @Test
    void pruneRemovesOnlyWhatACompleteListingDidNotSee() {
        var present = file("file-present", "Present.docx", Instant.now());
        seedItem("file-present", "Present.docx");
        seedItem("file-vanished", "Vanished.docx");
        pruneDue();
        when(session.delta(eq(DRIVE), isNull(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(present), null, "delta-link"));

        var work = claim(enqueue());
        var first = service.execute(work);
        assertEquals(ConnectorSyncPort.Result.CONTINUED, first, "removals are handed back in batches");
        assertEquals("DELETING", status("file-vanished"));
        assertNotEquals("DELETING", status("file-present"));

        var second = service.execute(claim(work.operationId()));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, second);
        assertNotNull(lastPrunedAt());
    }

    @Test
    void pruneRemovesNothingWhenTheListingCannotFinish() {
        seedItem("file-present", "Present.docx");
        pruneDue();
        when(session.delta(eq(DRIVE), isNull(), any())).thenThrow(
                new SharePointProviderException(
                        SharePointProviderException.Failure.UNAVAILABLE));

        var result = service.execute(claim(enqueue()));

        assertEquals(ConnectorSyncPort.Result.FAILED, result);
        assertNotEquals("DELETING", status("file-present"),
                "a site that cannot answer must never cause its documents to be deleted");
        assertNull(lastPrunedAt());
    }

    @Test
    void aRunLeftOpenByACancelledAttemptDoesNotBlockTheNextOne() {
        // An endless change log hands the run back with its checkpoint, so the run is still open.
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(), "next-page", null));
        assertEquals(ConnectorSyncPort.Result.CONTINUED, service.execute(claim(enqueue())));
        tx.executeWithoutResult(_ -> attempts.supersede(tenant, source));

        reset(session);
        when(session.root()).thenReturn(new SharePointGateway.RootSite(SITE, "https://contoso.sharepoint.com",
                "contoso.sharepoint.com"));
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(), null, "delta-link"));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        assertEquals(List.of("CANCELLED", "SUCCEEDED"), runStatuses());
    }

    @Test
    void aFailedAttemptClosesItsRun() {
        when(session.delta(eq(DRIVE), any(), any())).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.AUTHENTICATION));
        assertEquals(ConnectorSyncPort.Result.FAILED, service.execute(claim(enqueue())));

        assertEquals(List.of("FAILED"), runStatuses());
    }

    @Test
    void aSourceWhoseSyncFailedIsReportedFailedWithItsCode() {
        when(session.delta(eq(DRIVE), any(), any())).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.AUTHENTICATION));
        assertEquals(ConnectorSyncPort.Result.FAILED, service.execute(claim(enqueue())));

        var summary = new JdbcSourceQueryRepository(jdbc).summary(tenant, owner, source, true, true, true);
        assertEquals(SourceStatus.FAILED, summary.status());
        assertEquals("SOURCE_SHAREPOINT_AUTHENTICATION", summary.errorCode());
    }

    @Test
    void aStorageFailureIsAnItemErrorThatTheNextRunRetriesAndResolves() {
        var file = file("file-stored", "Stored.pdf", Instant.now());
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(file), null, "delta-link"));
        when(session.item(DRIVE, "file-stored")).thenReturn(file);
        when(session.content(any(), eq("contoso.sharepoint.com"), anyInt()))
                .thenReturn(new SharePointGateway.Content("Stored.pdf", "application/pdf", "stored".getBytes()));
        doThrow(new ObjectStorageException(ObjectStorageFailureCode.UNAVAILABLE, true, null))
                .doAnswer(storeObject).when(storage).write(any(), any(), any());
        var first = enqueue();

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(first)),
                "a failure isolated to one item does not fail the run");
        assertEquals("COMPLETED_WITH_ERRORS", attemptStatus(first));
        assertEquals(List.of("SUCCEEDED"), runStatuses());
        assertEquals(1, counter("acquisition_failed"));
        assertEquals("ABSENT", status("file-stored"));
        assertEquals("SOURCE_STORAGE_WRITE_UNAVAILABLE", jdbc.sql("""
                SELECT code FROM source_run_errors WHERE run_id = :run AND error_key = 'FILE:file-stored'
                  AND resolved_at IS NULL AND stage = 'STORAGE_WRITE'
                """).param("run", first.value()).query(String.class).single());
        var summary = new JdbcSourceQueryRepository(jdbc).summary(tenant, owner, source, true, true, true);
        assertNull(summary.errorCode(), "a run that completed with errors leaves no synchronization error");

        var second = enqueue();
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(second)));

        assertEquals("SUCCEEDED", attemptStatus(second));
        assertEquals("PENDING", status("file-stored"));
        assertEquals(second.value(), jdbc.sql("""
                SELECT resolved_by_run_id FROM source_run_errors
                WHERE run_id = :run AND error_key = 'FILE:file-stored' AND resolved_at IS NOT NULL
                """).param("run", first.value()).query(UUID.class).single(),
                "the next run retried the item and resolved its error");
    }

    @Test
    void failuresOfMoreThanThreeItemsAndATenthOfTheRunAbortItAndRetryTheAttempt() {
        var files = new ArrayList<SharePointGateway.DriveItem>();
        for (int index = 0; index < 5; index++) files.add(file("file-" + index, "Report " + index + ".pdf", Instant.now()));
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(files, null, "delta-link"));
        when(session.item(eq(DRIVE), any())).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.MALFORMED));
        var operation = enqueue();

        assertEquals(ConnectorSyncPort.Result.FAILED, service.execute(claim(operation)));

        assertEquals("NOT_STARTED", attemptStatus(operation), "the aborted attempt is retried");
        assertEquals("SOURCE_SYNC_ITEM_FAILURES_EXCEEDED", jdbc.sql("""
                SELECT error_code FROM source_sync_attempts WHERE id = :id
                """).param("id", operation.value()).query(String.class).single());
        assertTrue(jdbc.sql("""
                SELECT next_dispatch_at >= CURRENT_TIMESTAMP + INTERVAL '25 seconds'
                  AND error_message IS NOT NULL AND failure_attempts = 1
                  AND failure_window_failed = acquisition_failed AND acquisition_failed = 4
                FROM source_sync_attempts WHERE id = :id
                """).param("id", operation.value()).query(Boolean.class).single(),
                "the fourth failure aborts; failures are counted afresh by the retry");
        assertEquals(List.of("IN_PROGRESS"), runStatuses(), "the retry resumes the same run");
        assertEquals(4, jdbc.sql("SELECT COUNT(*) FROM source_run_errors WHERE run_id = :id AND stage = 'PROVIDER'")
                .param("id", operation.value()).query(Integer.class).single());
    }

    @Test
    void aThrottledRunWaitsAsLongAsMicrosoftAsked() {
        when(session.delta(eq(DRIVE), any(), any())).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.QUOTA, SharePointProviderException.Reason.UNCLASSIFIED,
                Duration.ofMinutes(5)));
        var operation = enqueue();

        assertEquals(ConnectorSyncPort.Result.FAILED, service.execute(claim(operation)));

        assertTrue(jdbc.sql("""
                SELECT status = 'NOT_STARTED' AND error_code = 'SOURCE_SHAREPOINT_QUOTA'
                  AND next_dispatch_at BETWEEN CURRENT_TIMESTAMP + INTERVAL '4 minutes'
                                           AND CURRENT_TIMESTAMP + INTERVAL '5 minutes 5 seconds'
                FROM source_sync_attempts WHERE id = :id
                """).param("id", operation.value()).query(Boolean.class).single(),
                "Retry-After replaces the 30 second backoff");
    }

    @Test
    void aRunResolvesItsSitesAndLibrariesOnceAcrossContinuations() {
        jdbc.sql("""
                UPDATE sharepoint_roots SET kind = 'SITE', drive_id = NULL
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
        when(session.libraries(SITE)).thenReturn(List.of(new SharePointGateway.Library(DRIVE, "Documents",
                "/sites/Finance/Shared Documents")));
        var pages = new AtomicInteger();
        when(session.delta(eq(DRIVE), any(), any())).thenAnswer(_ -> pages.incrementAndGet() < 20
                ? new SharePointGateway.DeltaPage(List.of(), "next-page", null)
                : new SharePointGateway.DeltaPage(List.of(), null, "delta-link"));
        var operation = enqueue();

        assertEquals(ConnectorSyncPort.Result.CONTINUED, service.execute(claim(operation)));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(operation)));

        verify(session, times(1)).libraries(SITE);
        verify(session, never()).sites(any());
    }

    @Test
    void anUnchangedFileTakesOneFenceOfFourLocks() {
        var files = new ArrayList<SharePointGateway.DriveItem>();
        for (int index = 0; index < 10; index++) {
            var file = file("file-" + index, "Report " + index + ".pdf", Instant.now());
            files.add(file);
            when(session.item(DRIVE, file.id())).thenReturn(file);
        }
        when(session.content(any(), eq("contoso.sharepoint.com"), anyInt())).thenAnswer(call ->
                new SharePointGateway.Content(((SharePointGateway.DriveItem) call.getArgument(0)).name(),
                        "application/pdf", ((SharePointGateway.DriveItem) call.getArgument(0)).id().getBytes()));
        when(session.delta(eq(DRIVE), any(), any())).thenReturn(new SharePointGateway.DeltaPage(List.of(), null, "l"));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        locks.reset();
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        int empty = locks.count();

        when(session.delta(eq(DRIVE), any(), any())).thenReturn(new SharePointGateway.DeltaPage(files, null, "l"));
        locks.reset();
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        int acquiring = locks.count() - empty;
        locks.reset();
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        int unchanged = locks.count() - empty;

        assertEquals(10, counter("unchanged"));
        // Before the shared engine an unchanged file took two fences (8 locks) and an acquired one four fences
        // plus a nested Source lock (19 locks).
        assertTrue(unchanged <= 10 * 4, "unchanged files took " + unchanged + " locking statements");
        assertTrue(acquiring <= 10 * 12, "acquired files took " + acquiring + " locking statements");
    }

    @Test
    void aScheduledPruneRunsWhenItIsDue() {
        seedItem("file-vanished", "Vanished.docx");
        pruneDue();
        when(session.delta(eq(DRIVE), isNull(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(), null, "delta-link"));

        assertEquals(1, service.enqueueDue(10));
        var operation = new SourceOperationId(jdbc.sql("""
                SELECT id FROM source_sync_attempts WHERE tenant_id = :tenant AND status = 'NOT_STARTED'
                """).param("tenant", tenant.value()).query(UUID.class).single());
        service.execute(claim(operation));
        assertEquals("DELETING", status("file-vanished"), "the scheduler's run is the prune that was due");
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(operation)));
        assertNotNull(lastPrunedAt());
    }

    @Test
    void aPruneThatCannotStartWaitsForTheNextRefreshSlot() {
        pruneDue();
        tx.executeWithoutResult(_ -> {
            attempts.postpone(tenant, source);
            runs.postponePrune(tenant, source);
        });

        assertTrue(jdbc.sql("""
                SELECT next_prune_at > CURRENT_TIMESTAMP
                   AND next_prune_at < CURRENT_TIMESTAMP + INTERVAL '2 hours'
                FROM sharepoint_sources WHERE tenant_id = :tenant
                """).param("tenant", tenant.value()).query(Boolean.class).single(),
                "neither retried on every scheduler tick nor pushed back by a whole prune interval");
    }

    @Test
    void aScopeChangeRereadsTheWholeNewScope() {
        jdbc.sql("UPDATE sharepoint_sources SET refresh_window_end = CURRENT_TIMESTAMP WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).update();
        long revision = jdbc.sql("SELECT scope_revision FROM source_sync_state WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Long.class).single();
        var scope = new Scope(ScopeMode.SPECIFIC, List.of("https://contoso.sharepoint.com/sites/Finance/Shared Documents"),
                List.of(), List.of(), true, false, 30, 168);
        tx.executeWithoutResult(_ -> sharePoint.replaceScope(tenant, source, revision, scope,
                List.of(new ResolvedRoot(SharePointSourceService.RootKind.LIBRARY,
                        "https://contoso.sharepoint.com/sites/Finance/Shared Documents", SITE, DRIVE, null,
                        "Documents")), "contoso.sharepoint.com"));
        assertNull(refreshWindowEnd(), "the documents of the old scope were hidden, so nothing may be skipped");

        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(), null, "delta-link"));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        verify(session).delta(DRIVE, null, null);
    }

    @Test
    void aFolderRootWalksItsSubfoldersAcrossContinuations() {
        folderRoot("folder-0");
        // A chain deeper than one execution's step budget, one file per folder.
        int depth = 20;
        for (int level = 0; level < depth; level++) {
            var children = new ArrayList<SharePointGateway.DriveItem>();
            children.add(file("file-" + level, "Report " + level + ".pdf", Instant.now()));
            if (level + 1 < depth) children.add(folder("folder-" + (level + 1)));
            when(session.children(DRIVE, "folder-" + level, null))
                    .thenReturn(new SharePointGateway.ItemPage(children, null));
        }
        when(session.item(any(), any())).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.NOT_FOUND));

        var operation = enqueue();
        assertEquals(ConnectorSyncPort.Result.CONTINUED, service.execute(claim(operation)));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(operation)));

        for (int level = 0; level < depth; level++) {
            assertEquals(1, ledger("file-" + level), "a file " + level + " folders deep is part of the scope");
        }
        verify(session, times(1)).children(DRIVE, "folder-" + (depth - 1), null);
    }

    @Test
    void aSourceThatCollectsOnlyPagesReadsNoLibrary() {
        collectPages();
        jdbc.sql("UPDATE sharepoint_sources SET include_documents = FALSE WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).update();
        when(session.pages(eq(SITE), any())).thenReturn(new SharePointGateway.SitePageList(List.of(), null));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        verify(session, never()).delta(any(), any(), any());
        verify(session, never()).children(any(), any(), any());
    }

    @Test
    void aRejectedChangeLinkRestartsTheLibrary() {
        jdbc.sql("UPDATE sharepoint_sources SET refresh_window_end = CURRENT_TIMESTAMP WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).update();
        var file = file("file-again", "Again.pdf", Instant.now());
        when(session.delta(eq(DRIVE), notNull(), any())).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.RESYNC_REQUIRED));
        when(session.delta(DRIVE, null, null))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(file), null, "delta-link"));
        when(session.item(any(), any())).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.NOT_FOUND));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        assertEquals(1, ledger("file-again"));
    }

    @Test
    void aPausedSourceIsNeitherScheduledNorWrittenBy() {
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(file("file-late", "Late.pdf", Instant.now())),
                        null, "delta-link"));
        var work = claim(enqueue());
        jdbc.sql("UPDATE connector_credential_pairs SET status = 'PAUSED' WHERE tenant_id = :tenant AND id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).update();

        assertEquals(ConnectorSyncPort.Result.CANCELLED, service.execute(work), "Source pause fences a claimed run");
        assertEquals("CANCELLED", jdbc.sql("SELECT status FROM source_sync_attempts WHERE id = :id")
                .param("id", work.operationId().value()).query(String.class).single());
        assertEquals("SOURCE_PAUSED", jdbc.sql("SELECT error_code FROM source_sync_attempts WHERE id = :id")
                .param("id", work.operationId().value()).query(String.class).single());
        assertEquals(0, ledger("file-late"));
        pruneDue();
        assertEquals(0, service.enqueueDue(10), "the scheduler leaves a paused Source alone");
    }

    @Test
    void aSourceThatDoesNotAdmitReadersBySharePointPermissionsReadsNone() {
        acquires(listed("file-1", "7"));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));

        verify(session, never()).itemRoleAssignments(any(), any(), any(), any());
        verify(session, never()).webRoleAssignments(any(), any());
        assertEquals(0, count("sharepoint_acl_snapshots"));
        assertEquals(0, count("sharepoint_group_runs"));
    }

    @Test
    void permissionsAreReadWhenAnItemIsAcquiredAndTheGroupsNamedThereAreAskedForTheirMembers() {
        autoSync();
        acquires(listed("file-1", "7"));
        when(session.itemRoleAssignments(HOST, WEB, LIST, "7")).thenReturn(List.of(
                assigned(user("An@contoso.onmicrosoft.com")), assigned(MEMBERS), assigned(entra(ACCOUNTING, "Kế toán")),
                new RoleAssignment(user("limited@contoso.com"), true)));
        when(session.webRoleAssignments(HOST, WEB)).thenReturn(List.of(assigned(MEMBERS),
                assigned(entra(ACCOUNTING, "Kế toán"))));
        when(session.siteGroupMembers(HOST, WEB, 9)).thenReturn(List.of(user("binh@contoso.com")));
        when(session.entraGroupMembers(ACCOUNTING, null)).thenReturn(new EntraMemberPage(List.of(
                new EntraMember(false, "user-1", "Chi", "chi@contoso.com"),
                new EntraMember(true, AUDIT, "Audit", null)), "next"));
        when(session.entraGroupMembers(ACCOUNTING, "next")).thenReturn(new EntraMemberPage(List.of(
                new EntraMember(false, "user-2", "Dung", "dung@contoso.com")), null));
        when(session.entraGroupMembers(AUDIT, null)).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.NOT_FOUND));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));

        assertEquals("[\"an@contoso.com\"]", snapshot("file-1", "user_emails"));
        assertEquals("[\"audit_" + AUDIT + "\", \"" + WEB.toLowerCase() + "::finance members\", \"kế toán_"
                + ACCOUNTING + "\"]", snapshot("file-1", "group_ids"), "a group inside a group is named too");
        assertEquals("false", snapshot("file-1", "access_public"));
        assertEquals(List.of("file-1"), aclChanges.stream().map(SourceAclChanged::providerFileId).toList());
        assertEquals(List.of("binh@contoso.com"), members(WEB.toLowerCase() + "::finance members"));
        assertEquals(List.of("chi@contoso.com", "dung@contoso.com"), members("kế toán_" + ACCOUNTING));
        assertEquals(List.of(), members("audit_" + AUDIT), "a group that is gone admits nobody");
        assertEquals(1, count("sharepoint_group_runs"));
        assertEquals("COMPLETED", jdbc.sql("""
                SELECT r.status FROM sharepoint_group_runs r JOIN sharepoint_sources s ON s.tenant_id = r.tenant_id
                    AND s.source_id = r.source_id AND s.active_group_generation = r.generation
                WHERE r.tenant_id = :tenant
                """).param("tenant", tenant.value()).query(String.class).single());
        // The item was asked about when it was acquired; the read of every item in the same run skips it.
        verify(session, times(1)).itemRoleAssignments(HOST, WEB, LIST, "7");
        assertFalse(permissionsDue(), "the next read of every item is an interval away");
    }

    @Test
    void aLaterRunSeesAChangeOfPermissionsOnAnItemWhoseContentDidNotChange() {
        autoSync();
        acquires(listed("file-1", "7"));
        when(session.itemRoleAssignments(HOST, WEB, LIST, "7")).thenReturn(List.of(assigned(user("an@contoso.com"))));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));

        // The change log reports nothing, and the permissions are not due: nothing is asked.
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(), null, "delta-link"));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        verify(session, times(1)).itemRoleAssignments(HOST, WEB, LIST, "7");

        permissionsDueNow();
        when(session.itemRoleAssignments(HOST, WEB, LIST, "7")).thenReturn(List.of(assigned(user("binh@contoso.com")),
                assigned(entra("c:0-.f|rolemanager|spo-grid-all-users/" + ACCOUNTING, "Everyone except external users"))));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));

        assertEquals("[\"binh@contoso.com\"]", snapshot("file-1", "user_emails"));
        assertEquals("true", snapshot("file-1", "access_public"));
        assertEquals("2", snapshot("file-1", "observation_revision"));
        assertEquals(2, aclChanges.size());

        // An unchanged answer is recorded without telling anybody.
        permissionsDueNow();
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        assertEquals(2, aclChanges.size());
    }

    @Test
    void anItemSharePointNoLongerHasIsReadByNobodyAndAFailedReadKeepsTheLastAnswer() {
        autoSync();
        acquires(listed("file-1", "7"), listed("file-2", "8"));
        when(session.itemRoleAssignments(eq(HOST), eq(WEB), eq(LIST), any()))
                .thenReturn(List.of(assigned(user("an@contoso.com"))));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));

        permissionsDueNow();
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(), null, "delta-link"));
        when(session.itemRoleAssignments(HOST, WEB, LIST, "7")).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.NOT_FOUND));
        when(session.itemRoleAssignments(HOST, WEB, LIST, "8")).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.MALFORMED));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));

        assertEquals("[]", snapshot("file-1", "user_emails"));
        assertEquals("SUCCEEDED", snapshot("file-1", "status"));
        assertEquals("[\"an@contoso.com\"]", snapshot("file-2", "user_emails"));
        assertEquals("FAILED", snapshot("file-2", "status"));
        assertEquals("SOURCE_SHAREPOINT_MALFORMED", snapshot("file-2", "error_code"));
    }

    @Test
    void groupMembershipIsReplacedOnlyByAReadThatReachedEverySite() {
        autoSync();
        acquires(listed("file-1", "7"));
        when(session.webRoleAssignments(HOST, WEB)).thenReturn(List.of(assigned(MEMBERS)));
        when(session.siteGroupMembers(HOST, WEB, 9)).thenReturn(List.of(user("an@contoso.com")));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        String group = WEB.toLowerCase() + "::finance members";
        assertEquals(List.of("an@contoso.com"), members(group));

        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(), null, "delta-link"));
        // Within the interval nothing is asked again.
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        verify(session, times(1)).webRoleAssignments(HOST, WEB);

        groupsDueNow();
        when(session.webRoleAssignments(HOST, WEB)).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.MALFORMED));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        assertEquals(List.of("an@contoso.com"), members(group), "a read that failed removes nobody");

        groupsDueNow();
        doReturn(List.of(assigned(MEMBERS))).when(session).webRoleAssignments(HOST, WEB);
        when(session.siteGroupMembers(HOST, WEB, 9)).thenReturn(List.of(user("binh@contoso.com")));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        assertEquals(List.of("binh@contoso.com"), members(group), "leaving the group ends its access");
        assertEquals(1, count("sharepoint_group_runs"), "earlier generations are removed");
        assertEquals(0, count("sharepoint_group_work"));
    }

    @Test
    void microsoftRefusingToListPermissionsFailsTheRunWithWhatTheApplicationLacks() {
        autoSync();
        acquires(listed("file-1", "7"));
        when(session.webRoleAssignments(HOST, WEB)).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.AUTHORIZATION));
        var operation = enqueue();

        assertEquals(ConnectorSyncPort.Result.FAILED, service.execute(claim(operation)));

        assertEquals("SOURCE_SHAREPOINT_SYNC_PERMISSIONS_DENIED", jdbc.sql(
                "SELECT error_code FROM source_sync_attempts WHERE id = :id").param("id", operation.value())
                .query(String.class).single());
        assertEquals("PENDING", status("file-1"), "what the run acquired stays, readable by nobody");
        assertNull(jdbc.sql("SELECT active_group_generation FROM sharepoint_sources WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Long.class).optional().orElse(null));
    }

    @Test
    void anItemHeldFromBeforeItsListWasRememberedIsAskedAboutOnceMore() {
        autoSync();
        seedItem("file-old", "Old.docx");
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(), null, "delta-link"));
        when(session.item(DRIVE, "file-old")).thenReturn(listed("file-old", "12"));
        when(session.itemRoleAssignments(HOST, WEB, LIST, "12")).thenReturn(List.of(assigned(user("an@contoso.com"))));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));

        assertEquals("[\"an@contoso.com\"]", snapshot("file-old", "user_emails"));
        assertEquals("12", jdbc.sql("SELECT list_item_id FROM sharepoint_items WHERE provider_file_id = 'file-old'")
                .query(String.class).single());
        // The site it belongs to is now known, so its groups are asked about from the next read on.
        permissionsDueNow();
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        verify(session, times(1)).item(DRIVE, "file-old");
    }

    @Test
    void aPageIsAskedAboutByItsAddressInItsSiteAndASharingLinkCountsOnlyWhenTheSourceSaysSo() {
        autoSync();
        collectPages();
        acquires(listed("file-1", "7"));
        var metadata = new SharePointGateway.SitePageMetadata("page-1", "Trang chủ",
                WEB + "/SitePages/Trang%20ch%E1%BB%A7.aspx", "etag-1", Instant.now());
        when(session.pages(eq(SITE), any())).thenReturn(new SharePointGateway.SitePageList(List.of(metadata), null));
        when(session.page(SITE, "page-1")).thenReturn(new SharePointGateway.PageContent(metadata,
                "{\"schema\":\"memoryos-sharepoint-page-v1\"}".getBytes(StandardCharsets.UTF_8)));
        when(session.pageRoleAssignments(HOST, WEB, "/sites/Finance/SitePages/Trang%20ch%E1%BB%A7.aspx"))
                .thenReturn(List.of(assigned(user("an@contoso.com"))));
        when(session.sharingLinkScopes(DRIVE, "file-1")).thenReturn(List.of("organization"));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));

        assertEquals("[\"an@contoso.com\"]", snapshot("page-1", "user_emails"));
        assertEquals("false", snapshot("file-1", "access_public"));
        verify(session, never()).sharingLinkScopes(any(), any());

        jdbc.sql("UPDATE sharepoint_sources SET treat_sharing_links_as_public = TRUE WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).update();
        permissionsDueNow();
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(), null, "delta-link"));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        assertEquals("true", snapshot("file-1", "access_public"));
    }

    private static final String HOST = "contoso.sharepoint.com";
    private static final String WEB = "https://contoso.sharepoint.com/sites/Finance";
    private static final String LIST = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String ACCOUNTING = "5f4dcc3b-5aa7-45d6-8cf3-99de8a2b4c1e";
    private static final String AUDIT = "9b2e4c1a-7d3f-4e5a-8b6c-1f2a3b4c5d6e";
    private static final Principal MEMBERS = new Principal(PrincipalType.SHAREPOINT_GROUP, 9, "Finance Members",
            "Finance Members", null);

    private void autoSync() {
        jdbc.sql("UPDATE connector_credential_pairs SET access_type = 'SYNC' WHERE tenant_id = :tenant AND id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).update();
    }

    /** The change log reports these files, and each can be downloaded. */
    private void acquires(SharePointGateway.DriveItem... files) {
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointGateway.DeltaPage(List.of(files), null, "delta-link"));
        for (var file : files) when(session.item(DRIVE, file.id())).thenReturn(file);
        when(session.content(any(), eq(HOST), anyInt()))
                .thenReturn(new SharePointGateway.Content("Report.pdf", "application/pdf", "content".getBytes()));
    }

    /** A file with the list row SharePoint keeps it in. */
    private SharePointGateway.DriveItem listed(String id, String listItemId) {
        var modified = Instant.now();
        return new SharePointGateway.DriveItem(id, id + ".pdf", false, false, 12, "application/pdf", "hash-" + id,
                "etag-" + id, modified, modified, "root-1", "/Reports", WEB + "/Shared%20Documents/" + id + ".pdf",
                null, DRIVE, new SharePointGateway.ListItem(LIST, listItemId, WEB));
    }

    private static RoleAssignment assigned(Principal principal) {
        return new RoleAssignment(principal, false);
    }

    private static Principal user(String userPrincipalName) {
        return new Principal(PrincipalType.USER, 11, "i:0#.f|membership|" + userPrincipalName, null, userPrincipalName);
    }

    private static Principal entra(String loginName, String title) {
        return new Principal(PrincipalType.ENTRA_GROUP, 12, loginName, title, null);
    }

    private String snapshot(String providerFileId, String column) {
        return jdbc.sql("SELECT CAST(" + column + " AS TEXT) FROM sharepoint_acl_snapshots "
                        + "WHERE tenant_id = :tenant AND source_id = :source AND file_id = :file")
                .param("tenant", tenant.value()).param("source", source.value()).param("file", providerFileId)
                .query(String.class).single();
    }

    /** The members of a group in the generation readers are checked against. */
    private List<String> members(String groupId) {
        return jdbc.sql("""
                SELECT m.member_email FROM sharepoint_group_members m
                JOIN sharepoint_sources s ON s.tenant_id = m.tenant_id AND s.source_id = m.source_id
                    AND s.active_group_generation = m.generation
                WHERE m.tenant_id = :tenant AND m.source_id = :source AND m.group_id = :group ORDER BY m.member_email
                """).param("tenant", tenant.value()).param("source", source.value()).param("group", groupId)
                .query(String.class).list();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Integer.class).single();
    }

    private boolean permissionsDue() {
        return jdbc.sql("SELECT next_permission_sync_at <= CURRENT_TIMESTAMP FROM sharepoint_sources WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Boolean.class).single();
    }

    private void permissionsDueNow() {
        jdbc.sql("""
                UPDATE sharepoint_sources SET next_permission_sync_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                WHERE tenant_id = :tenant
                """).param("tenant", tenant.value()).update();
    }

    private void groupsDueNow() {
        jdbc.sql("UPDATE sharepoint_group_runs SET started_at = started_at - INTERVAL '1 hour' WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).update();
    }

    private void folderRoot(String itemId) {
        jdbc.sql("""
                UPDATE sharepoint_roots SET kind = 'FOLDER', item_id = :item
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("item", itemId).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    private static SharePointGateway.DriveItem folder(String id) {
        return new SharePointGateway.DriveItem(id, id, true, false, 0, null, null, "etag-" + id, Instant.now(),
                Instant.now(), "root-1", "/Reports", null, null, DRIVE, null);
    }

    private String attemptStatus(SourceOperationId operation) {
        return jdbc.sql("SELECT status FROM source_sync_attempts WHERE id = :id")
                .param("id", operation.value()).query(String.class).single();
    }

    private List<String> runStatuses() {
        return jdbc.sql("SELECT status FROM sharepoint_sync_runs WHERE tenant_id = :tenant ORDER BY created_at")
                .param("tenant", tenant.value()).query(String.class).list();
    }

    private static ContentSha256 checksum(byte[] value) {
        try {
            return new ContentSha256(HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private SourceOperationId enqueue() {
        return Objects.requireNonNull(tx.execute(_ -> attempts.enqueue(tenant, source, 1L, SourceRunTrigger.MANUAL, owner).id()));
    }

    /** Stands in for the relay, which stamps the delivery a worker then claims. */
    private ConnectorSyncPort.Work claim(SourceOperationId operation) {
        UUID delivery = UUID.randomUUID();
        return Objects.requireNonNull(tx.execute(_ -> {
            jdbc.sql("""
                    UPDATE source_sync_attempts SET delivery_id = :delivery, dispatched_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = :tenant AND id = :id
                    """).param("delivery", delivery).param("tenant", tenant.value())
                    .param("id", operation.value()).update();
            return attempts.claim(tenant, operation, delivery).orElseThrow();
        }));
    }

    private SharePointGateway.DriveItem file(String id, String name, Instant modified) {
        return new SharePointGateway.DriveItem(id, name, false, false, 12, "application/pdf", "hash-" + id,
                "etag-" + id, modified, modified, "root-1", "/Reports",
                "https://contoso.sharepoint.com/sites/Finance/Shared%20Documents/" + name, null, DRIVE, null);
    }

    private static SharePointGateway.DriveItem tombstone(String id) {
        return new SharePointGateway.DriveItem(id, null, false, true, 0, null, null, null, null, null,
                "root-1", null, null, null, DRIVE, null);
    }

    private void seedTenant() {
        jdbc.sql("INSERT INTO actors (id) VALUES (:id)").param("id", owner.value()).update();
        jdbc.sql("""
                INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference)
                VALUES (:id, 'sharepoint-sync', 'SharePoint sync', 'ACTIVE', 'TEST')
                """).param("id", tenant.value()).update();
        jdbc.sql("""
                INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                VALUES (:tenant, :actor, 'OWNER', 'ACTIVE')
                """).param("tenant", tenant.value()).param("actor", owner.value()).update();
        jdbc.sql("""
                INSERT INTO credentials (id, tenant_id, name, credential_kind, status, owner_actor_id)
                VALUES (:id, :tenant, 'Entra app', 'SHAREPOINT_APP', 'ACTIVE', :actor)
                """).param("id", credential.value()).param("tenant", tenant.value())
                .param("actor", owner.value()).update();
        jdbc.sql("""
                INSERT INTO sharepoint_credentials (tenant_id, credential_id, directory_id, client_id, cloud,
                    auth_method, secret_ciphertext, secret_nonce, secret_key_version)
                VALUES (:tenant, :credential, :directory, :client, 'GLOBAL', 'CLIENT_SECRET',
                    :ciphertext, :nonce, 'v1')
                """).param("tenant", tenant.value()).param("credential", credential.value())
                .param("directory", UUID.randomUUID()).param("client", UUID.randomUUID())
                .param("ciphertext", new byte[32]).param("nonce", new byte[12]).update();
    }

    private void seedSource() {
        var scope = new Scope(ScopeMode.SPECIFIC, List.of("https://contoso.sharepoint.com/sites/Finance/Shared Documents"),
                List.of(), List.of(), true, false, 30, 168);
        tx.executeWithoutResult(_ -> sharePoint.create(tenant, source, owner, null, "Finance", credential,
                SourceAccess.PUBLIC, scope,
                List.of(new ResolvedRoot(SharePointSourceService.RootKind.LIBRARY,
                        "https://contoso.sharepoint.com/sites/Finance/Shared Documents", SITE, DRIVE, null,
                        "Documents")), "contoso.sharepoint.com"));
    }

    /** Seeds an item the Source already holds, as a completed acquisition would have left it. */
    private void seedItem(String providerFileId, String name) {
        UUID connector = jdbc.sql("""
                SELECT connector_id FROM connector_credential_pairs WHERE tenant_id = :tenant AND id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).query(UUID.class).single();
        UUID item = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO connector_items (id, tenant_id, connector_id, provider_file_id, content_sha256, status)
                VALUES (:id, :tenant, :connector, :file, :sha, 'PENDING')
                """).param("id", item).param("tenant", tenant.value()).param("connector", connector)
                .param("file", providerFileId).param("sha", "0".repeat(64)).update();
        jdbc.sql("""
                INSERT INTO sharepoint_items (tenant_id, source_id, provider_file_id, kind, drive_id, site_id, name)
                VALUES (:tenant, :source, :file, 'FILE', :drive, :site, :name)
                """).param("tenant", tenant.value()).param("source", source.value()).param("file", providerFileId)
                .param("drive", DRIVE).param("site", SITE).param("name", name).update();
    }

    private void pruneDue() {
        jdbc.sql("""
                UPDATE sharepoint_sources SET next_prune_at = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    private String status(String providerFileId) {
        return jdbc.sql("""
                SELECT i.status FROM connector_items i
                JOIN connector_credential_pairs p ON p.tenant_id = i.tenant_id AND p.connector_id = i.connector_id
                WHERE p.tenant_id = :tenant AND p.id = :source AND i.provider_file_id = :file
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("file", providerFileId).query(String.class).optional().orElse("ABSENT");
    }

    private int ledger(String providerFileId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM sharepoint_items
                WHERE tenant_id = :tenant AND source_id = :source AND provider_file_id = :file
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("file", providerFileId).query(Integer.class).single();
    }

    private long counter(String column) {
        return jdbc.sql("SELECT COALESCE(SUM(" + column + "), 0) FROM source_sync_attempts WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Long.class).single();
    }

    private @Nullable Timestamp refreshWindowEnd() {
        return jdbc.sql("SELECT refresh_window_end FROM sharepoint_sources WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Timestamp.class).optional().orElse(null);
    }

    private @Nullable Timestamp lastPrunedAt() {
        return jdbc.sql("SELECT last_pruned_at FROM sharepoint_sources WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Timestamp.class).optional().orElse(null);
    }
}
