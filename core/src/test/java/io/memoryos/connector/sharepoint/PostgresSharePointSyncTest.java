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
import io.memoryos.connector.SharePointSourceService;
import io.memoryos.connector.googledrive.GoogleDriveConnectionService;
import io.memoryos.connector.sync.LockingStatementCounter;
import io.memoryos.connector.sync.ProviderAuthorityService;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.connector.SharePointSourceService.Scope;
import io.memoryos.connector.SharePointSourceService.ScopeMode;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceRunTrigger;
import io.memoryos.connector.SourceStatus;
import io.memoryos.connector.sync.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointCredentialRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSourceRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSourceRepository.ResolvedRoot;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointSyncRepository;
import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.source.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.source.persistence.JdbcSourceQueryRepository;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository;
import io.memoryos.connector.sync.persistence.SyncTarget;
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
    private SharePointProvider.Session session;
    private JdbcSharePointSyncRepository runs;
    private JdbcSharePointSourceRepository sharePoint;
    private JdbcSourceSyncRepository attempts;
    private JdbcIndexAttemptRepository indexing;
    private ProviderAuthorityService authority;
    private OperationDispatchPort dispatch;
    private SourceSyncEngine service;
    private TransactionTemplate tx;
    private ObjectStorage storage;
    private Answer<Void> storeObject;

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
        var items = new JdbcSourceItemRepository(jdbc);
        attempts = new JdbcSourceSyncRepository(jdbc);
        runs = new JdbcSharePointSyncRepository(jdbc);
        sharePoint = new JdbcSharePointSourceRepository(jdbc, sources);
        var credentialRows = new JdbcSharePointCredentialRepository(jdbc, sources,
                new SharePointCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "test"));
        var currentConnections = new SharePointConnectionService(credentialRows, sharePoint,
                mock(SharePointProvider.class), manager);
        authority = new ProviderAuthorityService(
                mock(GoogleDriveConnectionService.class), currentConnections);
        indexing = new JdbcIndexAttemptRepository(jdbc, sources, documents, authority);
        dispatch = TestDatabase.transactionalProxy(new JdbcOperationDispatchRepository(jdbc),
                OperationDispatchPort.class, manager);

        session = mock(SharePointProvider.Session.class);
        when(session.root()).thenReturn(new SharePointProvider.RootSite(SITE, "https://contoso.sharepoint.com",
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
                List.of(new SharePointSyncTraversal(runs, sharePoint, attempts, connections)), manager);
        seedSource();
    }

    @Test
    void currentSharePointVersionCanReplayAndPublish() {
        var file = file("file-current", "Current.pdf", Instant.now());
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointProvider.DeltaPage(List.of(file), null, "delta-link"));
        when(session.item(DRIVE, "file-current")).thenReturn(file);
        when(session.content(any(), eq("contoso.sharepoint.com"), anyInt()))
                .thenReturn(new SharePointProvider.Content("Current.pdf", "application/pdf", "current".getBytes()));

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
                UPDATE sharepoint_credentials SET credential_revision = credential_revision + 1
                WHERE tenant_id = :tenant AND credential_id = :credential
                """).param("tenant", tenant.value()).param("credential", credential.value()).update();
        assertFalse(Boolean.TRUE.equals(tx.execute(_ -> indexing.canReplay(tenant, source, version))));
    }

    @Test
    void refreshStoresChangedContentAndQueuesItForIndexing() {
        var file = file("file-new", "Bao cao.pdf", Instant.now());
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointProvider.DeltaPage(List.of(file), null, "delta-link"));
        when(session.item(DRIVE, "file-new")).thenReturn(file);
        when(session.content(any(), eq("contoso.sharepoint.com"), anyInt()))
                .thenReturn(new SharePointProvider.Content("Bao cao.pdf", "application/pdf",
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
                .thenReturn(new SharePointProvider.DeltaPage(List.of(), null, "delta-link"));
        var metadata = new SharePointProvider.SitePageMetadata("page-1", "Trang chủ",
                "https://contoso.sharepoint.com/sites/Finance/SitePages/Home.aspx", "etag-1", Instant.now());
        when(session.pages(eq(SITE), any()))
                .thenReturn(new SharePointProvider.SitePageList(List.of(metadata), null));
        when(session.page(SITE, "page-1")).thenReturn(new SharePointProvider.PageContent(metadata,
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
                .thenReturn(new SharePointProvider.DeltaPage(List.of(), null, "delta-link"));
        when(session.pages(eq(SITE), any()))
                .thenReturn(new SharePointProvider.SitePageList(List.of(), null));

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
        when(session.delta(eq(DRIVE), any(), any())).thenReturn(new SharePointProvider.DeltaPage(
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
                .thenReturn(new SharePointProvider.DeltaPage(List.of(moved), null, "delta-link"));
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
                .thenReturn(new SharePointProvider.DeltaPage(List.of(present), null, "delta-link"));

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
                .thenReturn(new SharePointProvider.DeltaPage(List.of(), "next-page", null));
        assertEquals(ConnectorSyncPort.Result.CONTINUED, service.execute(claim(enqueue())));
        tx.executeWithoutResult(_ -> attempts.supersede(tenant, source));

        reset(session);
        when(session.root()).thenReturn(new SharePointProvider.RootSite(SITE, "https://contoso.sharepoint.com",
                "contoso.sharepoint.com"));
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointProvider.DeltaPage(List.of(), null, "delta-link"));

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
                .thenReturn(new SharePointProvider.DeltaPage(List.of(file), null, "delta-link"));
        when(session.item(DRIVE, "file-stored")).thenReturn(file);
        when(session.content(any(), eq("contoso.sharepoint.com"), anyInt()))
                .thenReturn(new SharePointProvider.Content("Stored.pdf", "application/pdf", "stored".getBytes()));
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
        var files = new ArrayList<SharePointProvider.DriveItem>();
        for (int index = 0; index < 5; index++) files.add(file("file-" + index, "Report " + index + ".pdf", Instant.now()));
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointProvider.DeltaPage(files, null, "delta-link"));
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
        when(session.libraries(SITE)).thenReturn(List.of(new SharePointProvider.Library(DRIVE, "Documents",
                "/sites/Finance/Shared Documents")));
        var pages = new AtomicInteger();
        when(session.delta(eq(DRIVE), any(), any())).thenAnswer(_ -> pages.incrementAndGet() < 20
                ? new SharePointProvider.DeltaPage(List.of(), "next-page", null)
                : new SharePointProvider.DeltaPage(List.of(), null, "delta-link"));
        var operation = enqueue();

        assertEquals(ConnectorSyncPort.Result.CONTINUED, service.execute(claim(operation)));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(operation)));

        verify(session, times(1)).libraries(SITE);
        verify(session, never()).sites(any());
    }

    @Test
    void anUnchangedFileTakesOneFenceOfFourLocks() {
        var files = new ArrayList<SharePointProvider.DriveItem>();
        for (int index = 0; index < 10; index++) {
            var file = file("file-" + index, "Report " + index + ".pdf", Instant.now());
            files.add(file);
            when(session.item(DRIVE, file.id())).thenReturn(file);
        }
        when(session.content(any(), eq("contoso.sharepoint.com"), anyInt())).thenAnswer(call ->
                new SharePointProvider.Content(((SharePointProvider.DriveItem) call.getArgument(0)).name(),
                        "application/pdf", ((SharePointProvider.DriveItem) call.getArgument(0)).id().getBytes()));
        when(session.delta(eq(DRIVE), any(), any())).thenReturn(new SharePointProvider.DeltaPage(List.of(), null, "l"));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        locks.reset();
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        int empty = locks.count();

        when(session.delta(eq(DRIVE), any(), any())).thenReturn(new SharePointProvider.DeltaPage(files, null, "l"));
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
                .thenReturn(new SharePointProvider.DeltaPage(List.of(), null, "delta-link"));

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
            attempts.postpone(SyncTarget.SHAREPOINT, tenant, source);
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
        long revision = jdbc.sql("SELECT scope_revision FROM sharepoint_sources WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Long.class).single();
        var scope = new Scope(ScopeMode.SPECIFIC, List.of("https://contoso.sharepoint.com/sites/Finance/Shared Documents"),
                List.of(), List.of(), true, false, 30, 168);
        tx.executeWithoutResult(_ -> sharePoint.replaceScope(tenant, source, revision, scope,
                List.of(new ResolvedRoot(SharePointSourceService.RootKind.LIBRARY,
                        "https://contoso.sharepoint.com/sites/Finance/Shared Documents", SITE, DRIVE, null,
                        "Documents")), "contoso.sharepoint.com"));
        assertNull(refreshWindowEnd(), "the documents of the old scope were hidden, so nothing may be skipped");

        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointProvider.DeltaPage(List.of(), null, "delta-link"));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        verify(session).delta(DRIVE, null, null);
    }

    @Test
    void aFolderRootWalksItsSubfoldersAcrossContinuations() {
        folderRoot("folder-0");
        // A chain deeper than one execution's step budget, one file per folder.
        int depth = 20;
        for (int level = 0; level < depth; level++) {
            var children = new ArrayList<SharePointProvider.DriveItem>();
            children.add(file("file-" + level, "Report " + level + ".pdf", Instant.now()));
            if (level + 1 < depth) children.add(folder("folder-" + (level + 1)));
            when(session.children(DRIVE, "folder-" + level, null))
                    .thenReturn(new SharePointProvider.ItemPage(children, null));
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
        when(session.pages(eq(SITE), any())).thenReturn(new SharePointProvider.SitePageList(List.of(), null));

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
                .thenReturn(new SharePointProvider.DeltaPage(List.of(file), null, "delta-link"));
        when(session.item(any(), any())).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.NOT_FOUND));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        assertEquals(1, ledger("file-again"));
    }

    @Test
    void aPausedSourceIsNeitherScheduledNorWrittenBy() {
        when(session.delta(eq(DRIVE), any(), any()))
                .thenReturn(new SharePointProvider.DeltaPage(List.of(file("file-late", "Late.pdf", Instant.now())),
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

    private void folderRoot(String itemId) {
        jdbc.sql("""
                UPDATE sharepoint_roots SET kind = 'FOLDER', item_id = :item
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("item", itemId).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    private static SharePointProvider.DriveItem folder(String id) {
        return new SharePointProvider.DriveItem(id, id, true, false, 0, null, null, "etag-" + id, Instant.now(),
                Instant.now(), "root-1", "/Reports", null, null, DRIVE);
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
        return Objects.requireNonNull(tx.execute(_ -> attempts.enqueue(SyncTarget.SHAREPOINT, tenant, source, 1L, SourceRunTrigger.MANUAL, owner).id()));
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

    private SharePointProvider.DriveItem file(String id, String name, Instant modified) {
        return new SharePointProvider.DriveItem(id, name, false, false, 12, "application/pdf", "hash-" + id,
                "etag-" + id, modified, modified, "root-1", "/Reports",
                "https://contoso.sharepoint.com/sites/Finance/Shared%20Documents/" + name, null, DRIVE);
    }

    private static SharePointProvider.DriveItem tombstone(String id) {
        return new SharePointProvider.DriveItem(id, null, false, true, 0, null, null, null, null, null,
                "root-1", null, null, null, DRIVE);
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
                    auth_method, connection_status, secret_ciphertext, secret_nonce, secret_key_version)
                VALUES (:tenant, :credential, :directory, :client, 'GLOBAL', 'CLIENT_SECRET', 'ACTIVE',
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
