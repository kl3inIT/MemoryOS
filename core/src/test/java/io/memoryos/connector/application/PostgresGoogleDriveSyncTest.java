package io.memoryos.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.TestDatabase;
import io.memoryos.connector.*;
import io.memoryos.connector.GoogleDriveSourceService.ScopeMode;
import io.memoryos.connector.persistence.*;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.application.DefaultExtractionArtifactService;
import io.memoryos.document.persistence.JdbcDocumentRepository;
import io.memoryos.document.persistence.JdbcExtractionArtifactRepository;
import io.memoryos.ingestion.*;
import io.memoryos.ingestion.application.DefaultIngestionCoordinator;
import io.memoryos.ingestion.application.SourceSyncProcessor;
import io.memoryos.ingestion.persistence.JdbcOperationDispatchRepository;
import io.memoryos.objectstorage.*;
import io.memoryos.objectstorage.application.DefaultObjectWriteService;
import io.memoryos.objectstorage.application.ObjectUploadProperties;
import io.memoryos.objectstorage.persistence.JdbcObjectWriteRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import io.memoryos.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
class PostgresGoogleDriveSyncTest {
    private JdbcClient jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private JdbcSourceRepository sources;
    private JdbcSourceItemRepository items;
    private JdbcSourceDocumentRepository mappings;
    private JdbcGoogleDriveSourceRepository roots;
    private JdbcSourceSyncRepository syncRows;
    private JdbcIndexAttemptRepository attempts;
    private GoogleDriveConnectionService connections;
    private GoogleDriveProvider.Session session;
    private ObjectWriteService writes;
    private OperationDispatchPort dispatch;
    private TenantId tenant;
    private SourceId source;
    private final io.memoryos.identity.ActorId scheduleOwner = new io.memoryos.identity.ActorId(UUID.randomUUID());
    private final AtomicLong revision = new AtomicLong(1);
    private final Map<String, GoogleDriveProvider.FileMetadata> files = new HashMap<>();
    private final Map<String, GoogleDriveProvider.FilePage> pages = new HashMap<>();
    private final Map<ObjectKey, byte[]> bytes = new HashMap<>();
    private final Map<ObjectKey, ObjectMetadata> metadata = new HashMap<>();
    private final Set<String> unsupported = new HashSet<>();
    private final List<String> calls = new ArrayList<>();
    private ObjectStorage storage;
    private Runnable duringExtraction = () -> {};

    @BeforeEach
    void initialize() throws Exception {
        var ds = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(ds);
        manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES (:id,'drive','Drive','ACTIVE','DRIVE-TEST')")
                .param("id", tenant.value()).update();
        sources = new JdbcSourceRepository(jdbc);
        var pair = tx.execute(_ -> sources.createFileSource(tenant, "Drive"));
        source = Objects.requireNonNull(pair).sourceId();
        jdbc.sql("UPDATE connectors SET connector_type='GOOGLE_DRIVE' WHERE id=:id").param("id", pair.connectorId()).update();
        jdbc.sql("UPDATE connector_credential_pairs SET access_type='RESTRICTED' WHERE id=:id").param("id", source.value()).update();
        items = new JdbcSourceItemRepository(jdbc);
        mappings = new JdbcSourceDocumentRepository(jdbc);
        roots = new JdbcGoogleDriveSourceRepository(jdbc);
        syncRows = new JdbcSourceSyncRepository(jdbc);
        session = mock(GoogleDriveProvider.Session.class);
        connections = mock(GoogleDriveConnectionService.class);
        when(connections.current(any(), any(), anyLong())).thenAnswer(i -> (long) i.getArgument(2) == revision.get());
        when(connections.state(any(), any())).thenAnswer(_ -> new GoogleDriveConnectionService.State(new CredentialId(UUID.randomUUID()), "owner@example.test", "ACTIVE", revision.get(), true));
        when(connections.open(any(), any())).thenAnswer(_ -> new GoogleDriveConnectionService.Connection(session, revision.get()));
        attempts = new JdbcIndexAttemptRepository(jdbc, sources, mappings, connections);
        dispatch = TestDatabase.transactionalProxy(new JdbcOperationDispatchRepository(jdbc), OperationDispatchPort.class, manager);
        storage = mock(ObjectStorage.class);
        doAnswer(i -> {
            ObjectKey key = i.getArgument(0);
            byte[] value = i.getArgument(1);
            bytes.put(key, value);
            metadata.put(key, new ObjectMetadata(value.length, i.getArgument(2), checksum(value)));
            return null;
        }).when(storage).write(any(), any(), any());
        when(storage.inspect(any())).thenAnswer(i -> metadata.get(i.getArgument(0)));
        when(storage.open(any())).thenAnswer(i -> {
            ObjectKey key = i.getArgument(0);
            var stream = new ByteArrayInputStream(bytes.get(key));
            return new ObjectContent() {
                public ObjectMetadata metadata() { return metadata.get(key); }
                public java.io.InputStream inputStream() { return stream; }
                public void close() {}
            };
        });
        writes = new DefaultObjectWriteService(new JdbcStoredObjectRepository(jdbc), new JdbcObjectWriteRepository(jdbc), storage,
                new ObjectUploadProperties(Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(1), 16), manager);
        files.put("folder", file("folder", true, "1"));
        files.put("root", file("my-drive", true, "1"));
        tx.executeWithoutResult(_ -> roots.replace(tenant, source, 1, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(new GoogleDriveSourceService.Root("folder", "Folder", "application/vnd.google-apps.folder"))));
        when(session.metadata(any())).thenAnswer(i -> {
            calls.add("metadata:" + i.getArgument(0));
            var value = files.get(i.getArgument(0));
            if (value == null) throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.NOT_FOUND);
            return value;
        });
        when(session.listFiles(any(), any())).thenAnswer(i -> {
            String parent = i.getArgument(0);
            String page = i.getArgument(1);
            calls.add("page:" + page);
            var scopedPage = pages.get(parent + ":" + (page == null ? "first" : page));
            if (scopedPage != null) return scopedPage;
            assertThat(parent).isIn("folder", "shared-folder");
            return pages.get(page == null ? "first" : page);
        });
        when(session.acquire(any())).thenAnswer(i -> {
            GoogleDriveProvider.FileMetadata file = i.getArgument(0);
            if (unsupported.contains(file.id())) throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.UNSUPPORTED);
            return new GoogleDriveProvider.AcquiredContent(file.name(), "text/plain",
                    (file.id() + ":" + file.version()).getBytes(StandardCharsets.UTF_8),
                    new SourceInputDescriptor(SourceInputFormat.BINARY, file.id(), file.version(), "https://drive.google.com/file/d/" + file.id() + "/view"));
        });
    }

    @Test
    void generalTraversesOnlyMyDriveThroughNestedPaginatedAndResumedFrontiers() {
        selectGeneral();
        pages.put("my-drive:first", new GoogleDriveProvider.FilePage(List.of(files.get("folder")), "root-next"));
        pages.put("my-drive:root-next", new GoogleDriveProvider.FilePage(List.of(child("direct", false, "my-drive")), null));
        files.put("direct", child("direct", false, "my-drive"));
        for (int page = 0; page < 20; page++) {
            var child = child("nested" + page, false, "folder");
            files.put(child.id(), child);
            pages.put(page == 0 ? "first" : "p" + page,
                    new GoogleDriveProvider.FilePage(List.of(child), page == 19 ? null : "p" + (page + 1)));
        }
        var operation = enqueue();
        assertThat(service().execute(claim(operation))).isEqualTo(ConnectorSyncPort.Result.CONTINUED);
        assertThat(dispatch.claim(OperationWorkload.INGESTION, 32)).isEmpty();
        finish(operation);

        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.SUCCEEDED);
        assertThat(jdbc.sql("SELECT provider_file_id FROM connector_items ORDER BY provider_file_id").query(String.class).list())
                .contains("direct", "nested0", "nested19").hasSize(21);
        assertThat(scalar("SELECT COUNT(*) FROM object_writes WHERE status='ADOPTED'")).isEqualTo(21);
        assertThat(scalar("SELECT COUNT(*) FROM google_drive_membership WHERE eligible AND root_id <> 'my-drive'")).isZero();
        verify(session, never()).listFiles("root", null);
        verify(session, never()).metadata("shared-with-me");
        verify(session, never()).metadata("workspace-drive");
        var configuration = scheduleConfiguration().configuration(scheduleOwner, source);
        assertThat(configuration.scopeMode()).isEqualTo(ScopeMode.GENERAL);
        assertThat(configuration.roots()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "changed", "trashed", "shared-drive"})
    void resumedGeneralTraversalCannotPruneWhenCurrentMyDriveRootCannotBeVerified(String failure) {
        selectGeneral();
        pages.put("my-drive:first", new GoogleDriveProvider.FilePage(List.of(files.get("folder")), null));
        listing(file("document", false, "1"));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        var documentsBefore = jdbc.sql("SELECT to_jsonb(d)::text FROM documents d").query(String.class).list();
        for (int page = 0; page < 20; page++) {
            pages.put(page == 0 ? "first" : "p" + page,
                    new GoogleDriveProvider.FilePage(List.of(), page == 19 ? null : "p" + (page + 1)));
        }
        var operation = enqueue();
        assertThat(service().execute(claim(operation))).isEqualTo(ConnectorSyncPort.Result.CONTINUED);
        assertThat(scalar("SELECT COUNT(*) FROM google_drive_frontier WHERE attempt_id='" + operation.value()
                + "' AND file_id='my-drive' AND state='DONE'")).isEqualTo(2);
        switch (failure) {
            case "missing" -> files.remove("root");
            case "changed" -> files.put("root", file("other-account-root", true, "1"));
            case "trashed" -> files.put("root", new GoogleDriveProvider.FileMetadata("my-drive", "My Drive",
                    "application/vnd.google-apps.folder", "2", null, null, true, List.of(), null, null));
            case "shared-drive" -> files.put("root", new GoogleDriveProvider.FileMetadata("my-drive", "Shared Drive",
                    "application/vnd.google-apps.folder", "2", null, null, false, List.of(), "my-drive", null));
            default -> throw new AssertionError(failure);
        }
        assertThat(service().execute(claim(operation))).isEqualTo(ConnectorSyncPort.Result.FAILED);
        finish(operation);

        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.FAILED);
        assertThat(jdbc.sql("SELECT to_jsonb(d)::text FROM documents d").query(String.class).list()).isEqualTo(documentsBefore);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE status='DELETING'")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM connector_cleanup_attempts")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-metadata", "trashed-metadata", "missing-list", "after-list"})
    void generalRootLossDuringEnumerationDoesNotTurnIntoAnEmptyCompletedGeneration(String failure) {
        selectGeneral();
        pages.put("my-drive:first", new GoogleDriveProvider.FilePage(List.of(files.get("folder")), null));
        listing(file("document", false, "1"));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        var documentsBefore = jdbc.sql("SELECT to_jsonb(d)::text FROM documents d").query(String.class).list();
        switch (failure) {
            case "missing-metadata" -> files.remove("my-drive");
            case "trashed-metadata" -> files.put("my-drive", new GoogleDriveProvider.FileMetadata("my-drive", "My Drive",
                    "application/vnd.google-apps.folder", "2", null, null, true, List.of(), null, null));
            case "missing-list" -> doThrow(new GoogleDriveProviderException(GoogleDriveProviderException.Failure.NOT_FOUND))
                    .when(session).listFiles("my-drive", null);
            case "after-list" -> doAnswer(_ -> {
                files.remove("root");
                return new GoogleDriveProvider.FilePage(List.of(), null);
            }).when(session).listFiles("folder", null);
            default -> throw new AssertionError(failure);
        }
        var operation = enqueue();
        finish(operation);

        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.FAILED);
        assertThat(jdbc.sql("SELECT to_jsonb(d)::text FROM documents d").query(String.class).list()).isEqualTo(documentsBefore);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE status='DELETING'")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM connector_cleanup_attempts")).isZero();
    }

    @Test
    void incompleteGeneralEnumerationRetainsUnseenDocumentsUntilACompleteGeneration() {
        selectGeneral();
        pages.put("my-drive:first", new GoogleDriveProvider.FilePage(List.of(files.get("folder")), null));
        listing(file("document", false, "1"));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        var documentsBefore = jdbc.sql("SELECT to_jsonb(d)::text FROM documents d").query(String.class).list();
        pages.put("first", new GoogleDriveProvider.FilePage(List.of(), "incomplete"));
        doThrow(new GoogleDriveProviderException(GoogleDriveProviderException.Failure.INCONSISTENT))
                .when(session).listFiles("folder", "incomplete");
        var incomplete = enqueue();
        finish(incomplete);

        assertThat(syncRows.find(tenant, incomplete).orElseThrow().status()).isEqualTo(SourceOperationStatus.FAILED);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE status='DELETING'")).isZero();
        assertThat(jdbc.sql("SELECT to_jsonb(d)::text FROM documents d").query(String.class).list()).isEqualTo(documentsBefore);
        listing();
        var complete = enqueue();
        finish(complete);
        assertThat(syncRows.find(tenant, complete).orElseThrow().status()).isEqualTo(SourceOperationStatus.SUCCEEDED);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE provider_file_id='document' AND status='DELETING'")).isEqualTo(1);
    }

    @Test
    void switchingGeneralToSpecificFencesOldWorkAndPrunesOnlyAfterTheNewScopeCompletes() {
        selectGeneral();
        scheduleConfiguration().updateSchedule(scheduleOwner, source, 1, 1);
        pages.put("my-drive:first", new GoogleDriveProvider.FilePage(List.of(files.get("folder")), null));
        listing(file("document", false, "1"));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        files.put("excluded-by-selection", child("excluded-by-selection", false, "my-drive"));
        pages.put("my-drive:first", new GoogleDriveProvider.FilePage(
                List.of(files.get("folder"), files.get("excluded-by-selection")), null));
        listing(file("document", false, "2"));
        finish(enqueue());
        var documentsBefore = jdbc.sql("SELECT to_jsonb(d)::text FROM documents d").query(String.class).list();
        var oldSync = claim(enqueue());
        duringExtraction = () -> scheduleConfiguration().replaceRoots(scheduleOwner, source, 3,
                ScopeMode.SPECIFIC, List.of(link("folder")));
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.SKIPPED);
        duringExtraction = () -> {};
        assertThat(service().execute(oldSync)).isEqualTo(ConnectorSyncPort.Result.SUPERSEDED);
        assertThat(jdbc.sql("SELECT to_jsonb(d)::text FROM documents d").query(String.class).list()).isEqualTo(documentsBefore);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE status='DELETING'")).isZero();
        pages.put("first", new GoogleDriveProvider.FilePage(List.of(files.get("document")), "incomplete"));
        doThrow(new GoogleDriveProviderException(GoogleDriveProviderException.Failure.INCONSISTENT))
                .when(session).listFiles("folder", "incomplete");
        var incomplete = enqueue();
        finish(incomplete);
        assertThat(syncRows.find(tenant, incomplete).orElseThrow().status()).isEqualTo(SourceOperationStatus.FAILED);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE status='DELETING'")).isZero();
        listing(files.get("document"));
        var complete = enqueue();
        finish(complete);
        assertThat(syncRows.find(tenant, complete).orElseThrow().status()).isEqualTo(SourceOperationStatus.SUCCEEDED);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE provider_file_id='excluded-by-selection' AND status='DELETING'")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE provider_file_id='document' AND status='DELETING'")).isZero();
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        var configuration = scheduleConfiguration().configuration(scheduleOwner, source);
        assertThat(configuration.scopeMode()).isEqualTo(ScopeMode.SPECIFIC);
        assertThat(configuration.roots()).extracting(GoogleDriveSourceService.Root::id).containsExactly("folder");
        assertThat(configuration.syncIntervalMinutes()).isEqualTo(1);
        assertThat(configuration.scheduleRevision()).isEqualTo(2);
    }

    @Test
    void rejectsOverlappingAndUnsupportedRootsWithoutChangingTheAcceptedScope() {
        var tenants = mock(io.memoryos.tenant.TenantAccessResolver.class);
        var owner = new io.memoryos.identity.ActorId(UUID.randomUUID());
        when(tenants.findActiveOwnerTenant(owner)).thenReturn(Optional.of(tenant));
        var configuration = new DefaultGoogleDriveSourceService(tenants, connections, roots, sources, syncRows,
                attempts, mappings, manager);
        files.put("document", file("document", false, "1"));
        var overlap = org.junit.jupiter.api.Assertions.assertThrows(SourceException.class,
                () -> configuration.replaceRoots(owner, source, 2, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("folder"), link("document"))));
        assertThat(overlap.code()).isEqualTo("SOURCE_GOOGLE_ROOTS_OVERLAP");
        files.put("shortcut", new GoogleDriveProvider.FileMetadata("shortcut", "Shortcut",
                "application/vnd.google-apps.shortcut", "1", null, null, false, List.of(), null, "elsewhere"));
        var unsupportedRoot = org.junit.jupiter.api.Assertions.assertThrows(SourceException.class,
                () -> configuration.replaceRoots(owner, source, 2, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("shortcut"))));
        assertThat(unsupportedRoot.code()).isEqualTo("SOURCE_GOOGLE_ROOT_UNSUPPORTED");
        assertThat(configuration.configuration(owner, source).roots()).extracting(GoogleDriveSourceService.Root::id)
                .containsExactly("folder");
        assertThat(configuration.replaceRoots(owner, source, 2, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("document"))).revision()).isEqualTo(3);
        var stale = org.junit.jupiter.api.Assertions.assertThrows(SourceException.class,
                () -> configuration.replaceRoots(owner, source, 2, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("folder"))));
        assertThat(stale.code()).isEqualTo("SOURCE_GOOGLE_REVISION_CONFLICT");
        assertThat(configuration.configuration(owner, source).roots()).extracting(GoogleDriveSourceService.Root::id)
                .containsExactly("document");
    }


    @Test
    void rejectsDuplicateLinksAndWholeDriveRootsWithoutChangingSelection() {
        var tenants = mock(io.memoryos.tenant.TenantAccessResolver.class);
        var owner = new io.memoryos.identity.ActorId(UUID.randomUUID());
        when(tenants.findActiveOwnerTenant(owner)).thenReturn(Optional.of(tenant));
        var configuration = new DefaultGoogleDriveSourceService(tenants, connections, roots, sources, syncRows,
                attempts, mappings, manager);
        org.junit.jupiter.api.Assertions.assertThrows(SourceException.class,
                () -> configuration.replaceRoots(owner, source, 2, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("folder"), "https://drive.google.com/open?id=folder")));
        assertThat(calls).isEmpty();
        files.put("my-drive", files.get("root"));
        org.junit.jupiter.api.Assertions.assertThrows(SourceException.class,
                () -> configuration.replaceRoots(owner, source, 2, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("my-drive"))));
        files.put("workspace-drive", new GoogleDriveProvider.FileMetadata("workspace-drive", "Entire team drive",
                "application/vnd.google-apps.folder", "1", null, null, false, List.of(), "workspace-drive", null));
        org.junit.jupiter.api.Assertions.assertThrows(SourceException.class,
                () -> configuration.replaceRoots(owner, source, 2, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("workspace-drive"))));
        assertThat(configuration.configuration(owner, source).roots())
                .extracting(GoogleDriveSourceService.Root::id).containsExactly("folder");
        assertThat(configuration.configuration(owner, source).revision()).isEqualTo(2);
    }

    @Test
    void legacyPhysicalDriveRootsFailClosedBeforeEnumerationOrAcquisition() {
        files.put("my-drive", files.get("root"));
        files.put("workspace-drive", new GoogleDriveProvider.FileMetadata("workspace-drive", "Entire team drive",
                "application/vnd.google-apps.folder", "1", null, null, false, List.of(), "workspace-drive", null));
        tx.executeWithoutResult(_ -> roots.replace(tenant, source, 2, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(
                new GoogleDriveSourceService.Root("my-drive", "My Drive", "application/vnd.google-apps.folder"),
                new GoogleDriveSourceService.Root("workspace-drive", "Team drive", "application/vnd.google-apps.folder"))));

        var operation = enqueue();
        finish(operation);

        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.FAILED);
        assertThat(scalar("SELECT COUNT(*) FROM google_drive_frontier WHERE state='UNSUPPORTED' AND error_code='SOURCE_GOOGLE_UNSUPPORTED'"))
                .isEqualTo(2);
        verify(session, never()).listFiles(any(), any());
        verify(session, never()).acquire(any());
        assertThat(scalar("SELECT COUNT(*) FROM connector_items")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM object_writes")).isZero();
    }

    @Test
    void acceptsAnExplicitSharedDriveFolderAndIndexesOnlyItsChildren() {
        var tenants = mock(io.memoryos.tenant.TenantAccessResolver.class);
        var owner = new io.memoryos.identity.ActorId(UUID.randomUUID());
        when(tenants.findActiveOwnerTenant(owner)).thenReturn(Optional.of(tenant));
        var configuration = new DefaultGoogleDriveSourceService(tenants, connections, roots, sources, syncRows,
                attempts, mappings, manager);
        files.put("shared-folder", new GoogleDriveProvider.FileMetadata("shared-folder", "Team folder",
                "application/vnd.google-apps.folder", "1", null, null, false, List.of(), "workspace-drive", null));
        listing(new GoogleDriveProvider.FileMetadata("document", "Team document", "text/plain", "1",
                null, null, false, List.of("shared-folder"), "workspace-drive", null));
        configuration.replaceRoots(owner, source, 2, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(link("shared-folder")));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items")).isEqualTo(1);
        assertThat(calls).doesNotContain("metadata:folder");
    }

    @Test
    void unconfiguredSourceCannotScheduleAccountWideSynchronization() {
        var tenants = mock(io.memoryos.tenant.TenantAccessResolver.class);
        var owner = new io.memoryos.identity.ActorId(UUID.randomUUID());
        when(tenants.findActiveOwnerTenant(owner)).thenReturn(Optional.of(tenant));
        var configuration = new DefaultGoogleDriveSourceService(tenants, connections, roots, sources, syncRows,
                attempts, mappings, manager);
        jdbc.sql("DELETE FROM google_drive_roots").update();
        org.junit.jupiter.api.Assertions.assertThrows(SourceException.class,
                () -> configuration.synchronize(owner, source));
        assertThat(service().enqueueDue(10)).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM source_sync_attempts")).isZero();
        assertThat(calls).isEmpty();
    }

    @Test
    void checkpointsPagesAndResumesWithoutRepublishingOrRestartingTheTraversal() {
        for (int page = 0; page < 20; page++) {
            var file = file("file" + page, false, "1");
            files.put(file.id(), file);
            pages.put(page == 0 ? "first" : "p" + page,
                    new GoogleDriveProvider.FilePage(List.of(file), page == 19 ? null : "p" + (page + 1)));
        }
        var operation = enqueue();
        var first = claim(operation);
        assertThat(service().execute(first)).isEqualTo(ConnectorSyncPort.Result.CONTINUED);
        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.NOT_STARTED);
        assertThat(dispatch.claim(OperationWorkload.INGESTION, 32)).isEmpty();
        var stale = service().execute(first);
        assertThat(stale).isEqualTo(ConnectorSyncPort.Result.SUPERSEDED);
        finish(operation);
        assertThat(calls.stream().filter("page:null"::equals).count()).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items")).isEqualTo(20);
        assertThat(scalar("SELECT COUNT(*) FROM object_writes WHERE status='ADOPTED'")).isEqualTo(20);
    }

    @Test
    void synchronizesOnlyExplicitRootsWithoutReadingUnrelatedFiles() {
        listing(file("document", false, "1"));
        files.put("shortcut", new GoogleDriveProvider.FileMetadata("shortcut", "Unrelated shortcut",
                "application/vnd.google-apps.shortcut", "1", null, null, false, List.of(), null, "elsewhere"));
        doThrow(new AssertionError("Unselected metadata must not be requested")).when(session).metadata("shortcut");
        var operation = enqueue();
        finish(operation);
        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.SUCCEEDED);
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        assertThat(calls).doesNotContain("metadata:shortcut");
        finish(enqueue());
        assertThat(scalar("SELECT COUNT(*) FROM connector_items")).isEqualTo(1);
        assertThat(calls).doesNotContain("metadata:shortcut");
    }

    @Test
    void trashingASelectedFolderReconcilesItsPreviouslyIndexedDescendants() {
        listing(file("document", false, "1"));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        files.put("folder", new GoogleDriveProvider.FileMetadata("folder", "Folder",
                "application/vnd.google-apps.folder", "2", null, null, true, List.of(), null, null));
        var operation = enqueue();
        finish(operation);
        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.SUCCEEDED);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE status='DELETING' AND provider_file_id='document'")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM google_drive_membership WHERE eligible")).isZero();
    }

    @Test
    void itemFailureReleasesConfirmedSnapshotsAndRetainsTheFailedFileForRetry() {
        listing(file("document", false, "1"), file("bad", false, "1"));
        unsupported.add("bad");
        var operation = enqueue();
        finish(operation);
        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.FAILED);
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        unsupported.clear();
        var recovery = enqueue();
        finish(recovery);
        assertThat(syncRows.find(tenant, recovery).orElseThrow().status()).isEqualTo(SourceOperationStatus.SUCCEEDED);
        assertThat(scalar("SELECT COUNT(*) FROM connector_item_versions WHERE provider_file_id='bad'")).isEqualTo(1);
    }

    @Test
    void reconciliationDefersInFlightIndexingUntilTheUnchangedInputIsConfirmed() {
        listing(file("document", false, "1"));
        finish(enqueue());
        var operation = enqueue();
        var syncWork = claim(operation);
        duringExtraction = () -> tx.executeWithoutResult(_ -> {
            sources.lock(tenant, source);
            syncRows.start(syncWork);
        });
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.SKIPPED);
        duringExtraction = () -> {};
        service().execute(syncWork);
        finish(operation);
        var delivery = dispatch.claim(OperationWorkload.INGESTION, 1).getFirst().delivery();
        var indexing = TestDatabase.transactionalProxy(attempts, ConnectorIndexingPort.class, manager);
        var retryWork = indexing.claim(tenant, delivery.operationId(), delivery.deliveryId()).orElseThrow();
        assertThat(indexing.retry(retryWork, "SOURCE_EXTRACTION_FAILED", 2, Duration.ofSeconds(1))).isTrue();
        jdbc.sql("UPDATE index_attempts SET next_dispatch_at = CURRENT_TIMESTAMP WHERE id = :id")
                .param("id", delivery.operationId().value()).update();
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        assertThat(scalar("SELECT COUNT(*) FROM connector_item_versions")).isEqualTo(1);
    }

    @Test
    void failedAcquisitionDoesNotPruneUnseenItems() {
        listing(file("keep", false, "1"), file("missing", false, "1"));
        finish(enqueue());
        listing(file("keep", false, "2"), file("bad", false, "1"));
        unsupported.add("bad");
        when(session.metadata("missing")).thenThrow(new AssertionError("removed or unseen items must not be reacquired"));
        var operation = enqueue();
        finish(operation);
        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.FAILED);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE status='DELETING'")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM connector_item_versions WHERE provider_file_id='keep'")).isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM google_drive_frontier WHERE state='UNSUPPORTED' AND file_id='bad'")).isEqualTo(1);
        listing(file("keep", false, "2"));
        var recovery = enqueue();
        finish(recovery);
        assertThat(syncRows.find(tenant, recovery).orElseThrow().status()).isEqualTo(SourceOperationStatus.SUCCEEDED);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE status='DELETING' AND provider_file_id='missing'")).isEqualTo(1);
    }

    @Test
    void indexesAdoptedBytesOfflineAndRollsBackPublicationAfterCredentialRevisionChanges() {
        listing(file("document", false, "1"));
        finish(enqueue());
        when(connections.open(any(), any())).thenThrow(new AssertionError("extraction must not open Google"));
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        UUID document = jdbc.sql("SELECT id FROM documents").query(UUID.class).single();
        UUID artifact = jdbc.sql("SELECT extraction_artifact_id FROM documents").query(UUID.class).single();
        assertThat(jdbc.sql("SELECT metadata_json FROM documents").query(String.class).single()).contains("document:1");
        assertThat(mappings.hasEligibleMapping(tenant, new io.memoryos.document.DocumentId(document))).isFalse();
        doAnswer(_ -> new GoogleDriveConnectionService.Connection(session, revision.get())).when(connections).open(any(), any());
        files.put("document", file("document", false, "2"));
        finish(enqueue());
        when(connections.open(any(), any())).thenThrow(new AssertionError("extraction must not open Google"));
        assertThat(index(true)).isEqualTo(IngestionCoordinator.Outcome.SKIPPED);
        assertThat(jdbc.sql("SELECT id FROM documents").query(UUID.class).single()).isEqualTo(document);
        assertThat(jdbc.sql("SELECT extraction_artifact_id FROM documents").query(UUID.class).single()).isEqualTo(artifact);
        assertThat(jdbc.sql("SELECT metadata_json FROM documents").query(String.class).single()).contains("document:1");
        doAnswer(_ -> new GoogleDriveConnectionService.Connection(session, revision.get())).when(connections).open(any(), any());
        listing(file("document", false, "2"));
        finish(enqueue());
        when(connections.open(any(), any())).thenThrow(new AssertionError("reauthorized replay must remain offline"));
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        assertThat(jdbc.sql("SELECT metadata_json FROM documents").query(String.class).single()).contains("document:2");
    }

    @Test
    void scopeReplacementAndExplicitRemovalCannotBeResurrectedByOldWork() {
        listing(file("document", false, "1"));
        finish(enqueue());
        var operation = enqueue();
        var old = claim(operation);
        tx.executeWithoutResult(_ -> {
            sources.lock(tenant, source);
            roots.replace(tenant, source, 2, GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(new GoogleDriveSourceService.Root("document", "document.txt", "text/plain")));
            syncRows.cancel(tenant, source);
        });
        assertThat(service().execute(old)).isEqualTo(ConnectorSyncPort.Result.SUPERSEDED);
        var item = new SourceItemId(jdbc.sql("SELECT id FROM connector_items").query(UUID.class).single());
        tx.executeWithoutResult(_ -> syncRows.exclude(tenant, source, item));
        var next = enqueue();
        finish(next);
        assertThat(scalar("SELECT COUNT(*) FROM connector_item_versions")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM google_drive_membership WHERE eligible")).isZero();
    }

    @Test
    void enqueueUsesTheSavedIntervalAndUnavailableGrantsPostponeInsteadOfEnqueueing() {
        scheduleConfiguration().updateSchedule(scheduleOwner, source, 1, 17);
        var operation = enqueue();
        assertScheduledFrom(operation, "created_at", 17);
        tx.executeWithoutResult(_ -> syncRows.cancel(tenant, source));
        jdbc.sql("UPDATE google_drive_sources SET next_sync_at = CURRENT_TIMESTAMP - INTERVAL '1 minute'").update();
        when(connections.current(any(), any(), anyLong())).thenReturn(false);
        tx.executeWithoutResult(_ -> {
            assertThat(service().enqueueDue(10)).isZero();
            assertThat(jdbc.sql("""
                    SELECT next_sync_at = CURRENT_TIMESTAMP + INTERVAL '17 minutes'
                    FROM google_drive_sources WHERE source_id = :source
                    """).param("source", source.value()).query(Boolean.class).single()).isTrue();
        });
        assertThat(scalar("SELECT COUNT(*) FROM source_sync_attempts")).isEqualTo(1);
        assertThat(calls).isEmpty();
    }

    @Test
    void inFlightSyncCompletesWithTheLatestIntervalAndKeepsPublishedDocuments() {
        listing(file("document", false, "1"));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        var documentsBefore = jdbc.sql("SELECT row_to_json(d)::text FROM documents d").query(String.class).list();
        var operation = enqueue();
        var work = claim(operation);
        doAnswer(_ -> {
            var updated = scheduleConfiguration().updateSchedule(scheduleOwner, source, 1, 23);
            assertThat(updated.revision()).isEqualTo(work.scopeRevision());
            assertThat(updated.pendingWork()).isTrue();
            assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.IN_PROGRESS);
            assertThat(scalar("SELECT generation FROM google_drive_sources")).isEqualTo(work.generation());
            return pages.get("first");
        }).when(session).listFiles(any(), any());

        assertThat(service().execute(work)).isEqualTo(ConnectorSyncPort.Result.COMPLETED);

        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.SUCCEEDED);
        assertScheduledFrom(operation, "completed_at", 23);
        assertThat(jdbc.sql("SELECT row_to_json(d)::text FROM documents d").query(String.class).list()).isEqualTo(documentsBefore);
        assertThat(scalar("SELECT COUNT(*) FROM connector_item_versions")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM index_attempts")).isEqualTo(1);
        assertThat(roots.configuration(tenant, source).scheduleRevision()).isEqualTo(2);
    }

    @Test
    void terminalItemFailureReschedulesUsingTheSavedInterval() {
        scheduleConfiguration().updateSchedule(scheduleOwner, source, 1, 19);
        listing(file("bad", false, "1"));
        unsupported.add("bad");
        var operation = enqueue();

        finish(operation);

        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.FAILED);
        assertThat(roots.configuration(tenant, source).errorCode()).isEqualTo("SOURCE_GOOGLE_INCOMPLETE");
        assertScheduledFrom(operation, "completed_at", 19);
    }

    @Test
    void retryExhaustionUsesAnIntervalSavedBetweenAttemptsWithoutChangingRetryBackoff() {
        var operation = enqueue();
        when(connections.open(any(), any())).thenThrow(
                new GoogleDriveProviderException(GoogleDriveProviderException.Failure.UNAVAILABLE));
        for (int attempt = 0; attempt < 6; attempt++) {
            tx.executeWithoutResult(_ -> {
                assertThat(service().execute(claim(operation))).isEqualTo(ConnectorSyncPort.Result.FAILED);
                assertThat(jdbc.sql("""
                        SELECT next_dispatch_at = CURRENT_TIMESTAMP + INTERVAL '30 seconds'
                        FROM source_sync_attempts WHERE id = :operation
                        """).param("operation", operation.value()).query(Boolean.class).single()).isTrue();
            });
            if (attempt == 0) scheduleConfiguration().updateSchedule(scheduleOwner, source, 1, 31);
            if (attempt < 5) {
                assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.NOT_STARTED);
            }
        }
        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.FAILED);
        assertScheduledFrom(operation, "completed_at", 31);
        assertThat(roots.configuration(tenant, source).errorCode()).isEqualTo("SOURCE_GOOGLE_UNAVAILABLE");
    }

    private void selectGeneral() {
        files.put("my-drive", files.get("root"));
        files.put("folder", child("folder", true, "my-drive"));
        var configuration = scheduleConfiguration().replaceRoots(scheduleOwner, source, 2, ScopeMode.GENERAL, List.of());
        assertThat(configuration.scopeMode()).isEqualTo(ScopeMode.GENERAL);
        assertThat(configuration.roots()).isEmpty();
    }

    private static GoogleDriveProvider.FileMetadata child(String id, boolean folder, String parent) {
        return new GoogleDriveProvider.FileMetadata(id, id, folder ? "application/vnd.google-apps.folder" : "text/plain",
                "1", null, null, false, List.of(parent), null, null);
    }

    private GoogleDriveSourceService scheduleConfiguration() {
        var tenants = mock(io.memoryos.tenant.TenantAccessResolver.class);
        when(tenants.findActiveOwnerTenant(scheduleOwner)).thenReturn(Optional.of(tenant));
        return new DefaultGoogleDriveSourceService(tenants, connections, roots, sources, syncRows, attempts, mappings, manager);
    }

    private void assertScheduledFrom(SourceOperationId operation, String timestamp, int minutes) {
        assertThat(jdbc.sql("""
                SELECT s.next_sync_at = a.%s + :minutes * INTERVAL '1 minute'
                FROM google_drive_sources s JOIN source_sync_attempts a
                  ON a.tenant_id = s.tenant_id AND a.source_id = s.source_id
                WHERE a.id = :operation
                """.formatted(timestamp)).param("minutes", minutes).param("operation", operation.value())
                .query(Boolean.class).single()).isTrue();
    }

    private IngestionCoordinator.Outcome index(boolean revokeDuringExtraction) {
        var delivery = dispatch.claim(OperationWorkload.INGESTION, 1).getFirst().delivery();
        var mapper = new ObjectMapper();
        SourceContentExtractor extractor = (input, size, name, descriptor) -> {
            try {
                String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                duringExtraction.run();
                if (revokeDuringExtraction) revision.incrementAndGet();
                return new DocumentContent("text/plain", name, text, Map.of("content", text));
            } catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
        };
        var indexing = TestDatabase.transactionalProxy(attempts, ConnectorIndexingPort.class, manager);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var coordinator = new DefaultIngestionCoordinator(indexing, mock(ConnectorCleanupPort.class),
                    new JdbcDocumentRepository(jdbc, mapper), extractor, storage, mock(StoredObjectRegistry.class), tx,
                    scheduler, new DefaultExtractionArtifactService(new JdbcExtractionArtifactRepository(jdbc), storage, mapper),
                    registry, new SourceSyncProcessor(service(), scheduler, registry));
            return coordinator.process(delivery);
        } finally {
            registry.close();
        }
    }

    private DefaultConnectorSyncService service() {
        return new DefaultConnectorSyncService(syncRows, sources, roots, items, attempts, mappings, connections, writes, manager);
    }

    private SourceOperationId enqueue() {
        return Objects.requireNonNull(tx.execute(_ -> {
            sources.lock(tenant, source);
            return syncRows.enqueue(tenant, source, revision.get()).id();
        }));
    }

    private ConnectorSyncPort.Work claim(SourceOperationId operation) {
        jdbc.sql("UPDATE source_sync_attempts SET next_dispatch_at=CURRENT_TIMESTAMP WHERE id=:id").param("id", operation.value()).update();
        var delivery = dispatch.claim(OperationWorkload.SOURCE_SYNC, 1).getFirst().delivery();
        return service().claim(tenant, operation, delivery.deliveryId()).orElseThrow();
    }

    private void finish(SourceOperationId operation) {
        for (int i = 0; i < 20; i++) {
            var state = syncRows.find(tenant, operation).orElseThrow().status();
            if (state == SourceOperationStatus.SUCCEEDED || state == SourceOperationStatus.FAILED) return;
            service().execute(claim(operation));
        }
        throw new AssertionError("sync did not terminate within bounded fixture executions");
    }

    private void listing(GoogleDriveProvider.FileMetadata... values) {
        for (var value : values) files.put(value.id(), value);
        pages.put("first", new GoogleDriveProvider.FilePage(List.of(values), null));
    }

    private static GoogleDriveProvider.FileMetadata file(String id, boolean folder, String version) {
        return new GoogleDriveProvider.FileMetadata(id, id + (folder ? "" : ".txt"),
                folder ? "application/vnd.google-apps.folder" : "text/plain", version, null, null,
                false, folder ? List.of() : List.of("folder"), null, null);
    }

    private long scalar(String query) { return jdbc.sql(query).query(Long.class).single(); }
    private static String link(String id) { return "https://drive.google.com/drive/folders/" + id; }
    private static ContentSha256 checksum(byte[] value) {
        try { return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
