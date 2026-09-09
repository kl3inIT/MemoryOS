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

import com.zaxxer.hikari.HikariDataSource;
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
import io.memoryos.iam.TenantId;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.application.DefaultIamAuthorization;
import io.memoryos.iam.persistence.IamAuthorizationRepository;
import io.memoryos.iam.persistence.IamLockRepository;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
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
    private HikariDataSource ds;

    @AfterEach
    void closeDatabase() {
        if (ds != null) {
            ds.close();
        }
    }

    private JdbcClient jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private JdbcSourceRepository sources;
    private JdbcSourceItemRepository items;
    private JdbcSourceDocumentRepository mappings;
    private JdbcGoogleDriveSourceRepository roots;
    private JdbcSourceSyncRepository syncRows;
    private JdbcGoogleDriveCredentialRepository credentials;
    private JdbcIndexAttemptRepository attempts;
    private GoogleDriveConnectionService connections;
    private GoogleDriveProvider.Session session;
    private ObjectWriteService writes;
    private OperationDispatchPort dispatch;
    private TenantId tenant;
    private SourceId source;
    private IamAuthorization authorization;
    private final io.memoryos.iam.ActorId scheduleOwner = new io.memoryos.iam.ActorId(UUID.randomUUID());
    private final AtomicLong revision = new AtomicLong(1);
    private final Map<String, GoogleDriveProvider.FileMetadata> files = new HashMap<>();
    private final Map<String, GoogleDriveProvider.FilePage> pages = new HashMap<>();
    private final Map<ObjectKey, byte[]> bytes = new HashMap<>();
    private final Map<ObjectKey, ObjectMetadata> metadata = new HashMap<>();
    private final Set<String> unsupported = new HashSet<>();
    private final List<String> calls = new ArrayList<>();
    private CredentialId credentialId;
    private final GoogleDriveLinkReader linkReader = mock(GoogleDriveLinkReader.class);
    private final Map<String, List<GoogleDriveLinkReader.Link>> links = new HashMap<>();
    private ObjectStorage storage;
    private Runnable duringExtraction = () -> {};

    @BeforeEach
    void initialize(TestInfo test) throws Exception {
        ds = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(ds);
        manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES (:id,'drive','Drive','ACTIVE','DRIVE-TEST')")
                .param("id", tenant.value()).update();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", scheduleOwner.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES (:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant.value()).param("actor", scheduleOwner.value()).update();
        jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name,system_key) VALUES (:tenant,:tenant,'Admin','ADMIN')")
                .param("tenant", tenant.value()).update();
        jdbc.sql("INSERT INTO iam_group_capability_grants(tenant_id,group_id,capability) VALUES (:tenant,:tenant,'IAM_ADMIN')")
                .param("tenant", tenant.value()).update();
        jdbc.sql("INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id) VALUES (:tenant,:tenant,:actor)")
                .param("tenant", tenant.value()).param("actor", scheduleOwner.value()).update();
        authorization = new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), new IamLockRepository(jdbc));
        sources = new JdbcSourceRepository(jdbc);
        var pair = tx.execute(_ -> sources.createFileSource(tenant, "Drive"));
        source = Objects.requireNonNull(pair).sourceId();
        jdbc.sql("UPDATE connectors SET connector_type='GOOGLE_DRIVE' WHERE id=:id").param("id", pair.connectorId()).update();
        jdbc.sql("UPDATE connector_credential_pairs SET access_type='RESTRICTED' WHERE id=:id").param("id", source.value()).update();
        items = new JdbcSourceItemRepository(jdbc);
        mappings = new JdbcSourceDocumentRepository(jdbc);
        roots = new JdbcGoogleDriveSourceRepository(jdbc);
        syncRows = new JdbcSourceSyncRepository(jdbc);
        credentials = new JdbcGoogleDriveCredentialRepository(jdbc, sources,
                new GoogleDriveCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "test"),
                mappings, syncRows);
        try (var client = new GoogleDriveOAuthClient("fixture.apps.googleusercontent.com", "fixture-secret".getBytes(StandardCharsets.UTF_8));
                var grant = new GoogleDriveAuthorizationService.Grant("fixture-subject", "fixture@example.test",
                        GoogleDriveAuthorizationService.REQUIRED_SCOPES, "fixture-refresh".getBytes(StandardCharsets.UTF_8))) {
            credentialId = Objects.requireNonNull(tx.execute(_ -> credentials.create(tenant, "Fixture credential", grant, client)));
        }
        jdbc.sql("UPDATE connector_credential_pairs SET credential_id=:credential WHERE id=:source")
                .param("credential", credentialId.value()).param("source", source.value()).update();
        session = mock(GoogleDriveProvider.Session.class);
        connections = mock(GoogleDriveConnectionService.class);
        when(connections.current(any(), any(), anyLong())).thenAnswer(i -> (long) i.getArgument(2) == revision.get());
        when(connections.state(any(), any())).thenAnswer(_ -> new GoogleDriveConnectionService.State(credentialId, "owner@example.test", "ACTIVE", revision.get(), true));
        when(connections.open(any(), any())).thenAnswer(_ -> new GoogleDriveConnectionService.Connection(session, revision.get()));
        when(connections.openCredential(any(), any())).thenAnswer(_ -> new GoogleDriveConnectionService.Connection(session, revision.get()));
        when(linkReader.read(any())).thenAnswer(invocation -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            GoogleDriveProvider.AcquiredContent content = invocation.getArgument(0);
            return links.getOrDefault(content.descriptor().providerFileId(), List.of());
        });
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
        var scopeMode = test.getTags().contains("general-scope") ? ScopeMode.GENERAL : ScopeMode.SPECIFIC;
        String rootId = scopeMode == ScopeMode.GENERAL ? "my-drive" : "folder";
        if (scopeMode == ScopeMode.GENERAL) {
            files.put("my-drive", files.get("root"));
            files.put("folder", child("folder", true, "my-drive"));
        }
        jdbc.sql("INSERT INTO google_drive_sources (tenant_id, source_id, scope_mode, revision) VALUES (:tenant, :source, :mode, 2)")
                .param("tenant", tenant.value()).param("source", source.value()).param("mode", scopeMode.name()).update();
        jdbc.sql("""
                INSERT INTO google_drive_roots (tenant_id, source_id, file_id, name, mime_type)
                VALUES (:tenant, :source, :root, :root, 'application/vnd.google-apps.folder')
                """).param("tenant", tenant.value()).param("source", source.value()).param("root", rootId).update();
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
    @Tag("general-scope")
    void generalTraversesOnlyMyDriveThroughNestedPaginatedAndResumedFrontiers() {
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
        assertThat(scheduleConfiguration().selectionDraft(scheduleOwner, source).links()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "changed", "trashed", "shared-drive"})
    @Tag("general-scope")
    void resumedGeneralTraversalCannotPruneWhenCurrentMyDriveRootCannotBeVerified(String failure) {
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
    @Tag("general-scope")
    void generalRootLossDuringEnumerationDoesNotTurnIntoAnEmptyCompletedGeneration(String failure) {
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
    @Tag("general-scope")
    void incompleteGeneralEnumerationRetainsUnseenDocumentsUntilACompleteGeneration() {
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
    void replacingSpecificLinksFencesOldWorkAndPrunesOnlyAfterTheNewSelectionCompletes() {
        scheduleConfiguration().updateSchedule(scheduleOwner, source, 1, 1);
        listing(file("document", false, "1"));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        files.put("my-drive", files.get("root"));
        files.put("excluded-by-selection", child("excluded-by-selection", false, "my-drive"));
        replaceAndActivate(scheduleConfiguration(), scheduleOwner, 2, List.of(link("document"), link("excluded-by-selection")), List.of());
        listing(file("document", false, "2"));
        finish(enqueue());
        var documentsBefore = jdbc.sql("SELECT to_jsonb(d)::text FROM documents d").query(String.class).list();
        var oldSync = claim(enqueue());
        duringExtraction = () -> replaceAndActivate(scheduleConfiguration(), scheduleOwner, 3, List.of(link("folder")), List.of());
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
        assertThat(selected(scheduleConfiguration(), GoogleDriveSourceService.SelectionKind.FOLDER))
                .extracting(GoogleDriveSourceService.SelectionItem::id).containsExactly("folder");
        assertThat(configuration.syncIntervalMinutes()).isEqualTo(1);
        assertThat(configuration.scheduleRevision()).isEqualTo(2);
    }

    @Test
    void rejectsOverlappingAndUnsupportedRootsWithoutChangingTheAcceptedScope() {
        var owner = scheduleOwner;
        var configuration = new DefaultGoogleDriveSourceService(authorization, connections, roots, sources, syncRows, attempts, mappings, org.mockito.Mockito.mock(io.memoryos.connector.GoogleDriveLinkReader.class), manager, new JdbcGoogleDriveSelectionRepository(jdbc), credentials, new GoogleDriveSelectionPolicy(1000, 3145728), new JdbcSourceGroupRepository(jdbc));
        files.put("document", file("document", false, "1"));
        var overlap = finishSelection(configuration, submitSelection(configuration, owner, 2, ScopeMode.SPECIFIC, List.of(link("folder"), link("document")), List.of()));
        assertThat(overlap.status()).isEqualTo(SourceOperationStatus.FAILED);
        assertThat(overlap.errorCode()).isEqualTo("SOURCE_GOOGLE_ROOTS_OVERLAP");
        files.put("shortcut", new GoogleDriveProvider.FileMetadata("shortcut", "Shortcut",
                "application/vnd.google-apps.shortcut", "1", null, null, false, List.of(), null, "elsewhere"));
        var unsupportedRoot = finishSelection(configuration, submitSelection(configuration, owner, 2, ScopeMode.SPECIFIC, List.of(link("shortcut")), List.of()));
        assertThat(unsupportedRoot.status()).isEqualTo(SourceOperationStatus.FAILED);
        assertThat(unsupportedRoot.errorCode()).isEqualTo("SOURCE_GOOGLE_ROOT_UNSUPPORTED");
        assertThat(selected(configuration, GoogleDriveSourceService.SelectionKind.FOLDER))
                .extracting(GoogleDriveSourceService.SelectionItem::id).containsExactly("folder");
        replaceAndActivate(configuration, owner, 2, List.of(link("document")), List.of());
        assertThat(configuration.configuration(owner, source).revision()).isEqualTo(3);
        var stale = org.junit.jupiter.api.Assertions.assertThrows(SourceException.class,
                () -> replaceAndActivate(configuration, owner, 2, List.of(link("folder")), List.of()));
        assertThat(stale.code()).isEqualTo("SOURCE_GOOGLE_REVISION_CONFLICT");
        assertThat(selected(configuration, GoogleDriveSourceService.SelectionKind.FILE))
                .extracting(GoogleDriveSourceService.SelectionItem::id).containsExactly("document");
    }


    @Test
    void rejectsDuplicateLinksAndWholeDriveRootsWithoutChangingSelection() {
        var owner = scheduleOwner;
        var configuration = new DefaultGoogleDriveSourceService(authorization, connections, roots, sources, syncRows, attempts, mappings, org.mockito.Mockito.mock(io.memoryos.connector.GoogleDriveLinkReader.class), manager, new JdbcGoogleDriveSelectionRepository(jdbc), credentials, new GoogleDriveSelectionPolicy(1000, 3145728), new JdbcSourceGroupRepository(jdbc));
        org.junit.jupiter.api.Assertions.assertThrows(SourceException.class,
                () -> replaceAndActivate(configuration, owner, 2, List.of(link("folder"), "https://drive.google.com/open?id=folder"), List.of()));
        assertThat(calls).isEmpty();
        files.put("my-drive", files.get("root"));
        assertThat(finishSelection(configuration, submitSelection(configuration, owner, 2, ScopeMode.SPECIFIC, List.of(link("my-drive")), List.of())).status()).isEqualTo(SourceOperationStatus.FAILED);
        files.put("workspace-drive", new GoogleDriveProvider.FileMetadata("workspace-drive", "Entire team drive",
                "application/vnd.google-apps.folder", "1", null, null, false, List.of(), "workspace-drive", null));
        assertThat(finishSelection(configuration, submitSelection(configuration, owner, 2, ScopeMode.SPECIFIC, List.of(link("workspace-drive")), List.of())).status()).isEqualTo(SourceOperationStatus.FAILED);
        assertThat(selected(configuration, GoogleDriveSourceService.SelectionKind.FOLDER))
                .extracting(GoogleDriveSourceService.SelectionItem::id).containsExactly("folder");
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
        var owner = scheduleOwner;
        var configuration = new DefaultGoogleDriveSourceService(authorization, connections, roots, sources, syncRows, attempts, mappings, org.mockito.Mockito.mock(io.memoryos.connector.GoogleDriveLinkReader.class), manager, new JdbcGoogleDriveSelectionRepository(jdbc), credentials, new GoogleDriveSelectionPolicy(1000, 3145728), new JdbcSourceGroupRepository(jdbc));
        files.put("shared-folder", new GoogleDriveProvider.FileMetadata("shared-folder", "Team folder",
                "application/vnd.google-apps.folder", "1", null, null, false, List.of(), "workspace-drive", null));
        listing(new GoogleDriveProvider.FileMetadata("document", "Team document", "text/plain", "1",
                null, null, false, List.of("shared-folder"), "workspace-drive", null));
        replaceAndActivate(configuration, owner, 2, List.of(link("shared-folder")), List.of());
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items")).isEqualTo(1);
        assertThat(calls).doesNotContain("metadata:folder");
    }

    @Test
    void unconfiguredSourceCannotScheduleAccountWideSynchronization() {
        var owner = scheduleOwner;
        var configuration = new DefaultGoogleDriveSourceService(authorization, connections, roots, sources, syncRows, attempts, mappings, org.mockito.Mockito.mock(io.memoryos.connector.GoogleDriveLinkReader.class), manager, new JdbcGoogleDriveSelectionRepository(jdbc), credentials, new GoogleDriveSelectionPolicy(1000, 3145728), new JdbcSourceGroupRepository(jdbc));
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
        jdbc.sql("UPDATE index_attempts SET next_dispatch_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = :id")
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

    private static GoogleDriveProvider.FileMetadata child(String id, boolean folder, String parent) {
        return new GoogleDriveProvider.FileMetadata(id, id, folder ? "application/vnd.google-apps.folder" : "text/plain",
                "1", null, null, false, List.of(parent), null, null);
    }

    @Test
    void discoveryRequiresApprovalAndSelectedOrphansUseTheSameDeduplicatedSyncPipeline() {
        listing(file("left", false, "1"), file("right", false, "1"));
        files.put("remote", new GoogleDriveProvider.FileMetadata("remote", "Remote document", "text/plain",
                "1", null, null, false, List.of(), null, null));
        var link = new GoogleDriveLinkReader.Link(link("remote"), "Sheet one!B2");
        links.put("left", List.of(link, link,
                new GoogleDriveLinkReader.Link(link("remote"), "Sheet one!B3"),
                new GoogleDriveLinkReader.Link(link("remote"), "Sheet one!B4"),
                new GoogleDriveLinkReader.Link(link("remote"), "Sheet one!B5")));
        links.put("right", List.of(
                new GoogleDriveLinkReader.Link(link("remote"), "Paragraph 1"),
                new GoogleDriveLinkReader.Link(link("remote"), "Paragraph 2"),
                new GoogleDriveLinkReader.Link(link("remote"), "Paragraph 3"),
                new GoogleDriveLinkReader.Link(link("remote"), "Paragraph 4")));
        var configuration = scheduleConfiguration();

        var discovered = configuration.discoverLinkedDocuments(scheduleOwner, source, 2);
        assertThat(discovered.revision()).isEqualTo(2);
        assertThat(discovered.counts().linkedDocuments()).isEqualTo(1);
        assertThat(selected(configuration, GoogleDriveSourceService.SelectionKind.LINKED)).singleElement().satisfies(document -> {
            assertThat(document.id()).isEqualTo("remote");
            assertThat(document.selected()).isFalse();
            assertThat(document.coveredByRoots()).isFalse();
            assertThat(document.origins()).extracting(GoogleDriveSourceService.LinkOrigin::parentId)
                    .containsExactly("left", "left", "left", "left", "right", "right", "right", "right");
            assertThat(document.origins()).extracting(GoogleDriveSourceService.LinkOrigin::location)
                    .containsExactly("Sheet one!B2", "Sheet one!B3", "Sheet one!B4", "Sheet one!B5",
                            "Paragraph 1", "Paragraph 2", "Paragraph 3", "Paragraph 4");
        });
        assertThat(scalar("SELECT COUNT(*) FROM connector_items")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM source_sync_attempts")).isZero();
        verify(session, never()).acquire(files.get("remote"));
        finish(enqueue());
        assertThat(jdbc.sql("SELECT provider_file_id FROM connector_items").query(String.class).list()).containsExactlyInAnyOrder("left", "right");

        replaceAndActivate(configuration, scheduleOwner, 2, List.of(link("folder")), List.of("remote"));
        org.mockito.Mockito.clearInvocations(linkReader);
        finish(configuration.synchronize(scheduleOwner, source).id());
        assertThat(jdbc.sql("SELECT provider_file_id FROM connector_items").query(String.class).list())
                .containsExactlyInAnyOrder("left", "right", "remote");
        for (int i = 0; i < 3; i++) assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        assertThat(scalar("SELECT COUNT(*) FROM documents")).isEqualTo(3);
        org.mockito.Mockito.verifyNoInteractions(linkReader);

        links.clear();
        files.put("child-link", new GoogleDriveProvider.FileMetadata("child-link", "Child", "text/plain", "1",
                null, null, false, List.of(), null, null));
        links.put("remote", List.of(new GoogleDriveLinkReader.Link(link("child-link"), "Paragraph 2")));
        configuration.discoverLinkedDocuments(scheduleOwner, source, 3);
        assertThat(selected(configuration, GoogleDriveSourceService.SelectionKind.LINKED)).filteredOn(document -> document.id().equals("remote")).singleElement()
                .satisfies(document -> { assertThat(document.selected()).isTrue(); assertThat(document.origins()).isEmpty(); });
        assertThat(selected(configuration, GoogleDriveSourceService.SelectionKind.LINKED)).filteredOn(document -> document.id().equals("child-link")).singleElement()
                .satisfies(document -> {
                    assertThat(document.selected()).isFalse();
                    assertThat(document.origins()).containsExactly(new GoogleDriveSourceService.LinkOrigin(
                            "remote", "remote", "Remote document", "Paragraph 2"));
                });
        org.mockito.Mockito.clearInvocations(linkReader);
        jdbc.sql("UPDATE google_drive_sources SET next_sync_at=CURRENT_TIMESTAMP - INTERVAL '1 minute'").update();
        assertThat(service().enqueueDue(10)).isEqualTo(1);
        finish(new SourceOperationId(jdbc.sql("SELECT id FROM source_sync_attempts WHERE status='NOT_STARTED'").query(UUID.class).single()));
        org.mockito.Mockito.verifyNoInteractions(linkReader);
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE provider_file_id='remote' AND status='DELETING'")).isZero();
        verify(session, never()).acquire(files.get("child-link"));
        files.remove("remote");
        configuration.discoverLinkedDocuments(scheduleOwner, source, 3);
        assertThat(selected(configuration, GoogleDriveSourceService.SelectionKind.LINKED)).singleElement().satisfies(document -> {
            assertThat(document.id()).isEqualTo("remote");
            assertThat(document.name()).isEqualTo("Remote document");
            assertThat(document.selected()).isTrue();
            assertThat(document.status()).isEqualTo(GoogleDriveSourceService.LinkedDocumentStatus.UNAVAILABLE);
        });

        replaceAndActivate(configuration, scheduleOwner, 3, List.of(link("folder")), List.of());
        finish(enqueue());
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE provider_file_id='remote' AND status='DELETING'")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM google_drive_membership WHERE excluded")).isZero();
    }

    @Test
    void coveredTargetsAreDeduplicatedAndRemovingApprovalDoesNotExcludeFolderContent() {
        listing(file("document", false, "1"));
        links.put("document", List.of(new GoogleDriveLinkReader.Link(link("document"), "Self reference")));
        var configuration = scheduleConfiguration();
        configuration.discoverLinkedDocuments(scheduleOwner, source, 2);
        assertThat(selected(configuration, GoogleDriveSourceService.SelectionKind.LINKED)).singleElement()
                .satisfies(document -> assertThat(document.coveredByRoots()).isTrue());
        replaceAndActivate(configuration, scheduleOwner, 2, List.of(link("folder")), List.of("document"));
        var operation = enqueue();
        finish(operation);
        assertThat(scalar("SELECT COUNT(*) FROM google_drive_frontier WHERE file_id='document' AND attempt_id='" + operation.value() + "'")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM connector_item_versions")).isEqualTo(1);
        replaceAndActivate(configuration, scheduleOwner, 3, List.of(link("folder")), List.of());
        finish(enqueue());
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE status='DELETING'")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM google_drive_membership WHERE file_id='document' AND eligible AND NOT excluded")).isEqualTo(1);
    }

    @Test
    void approvedTargetBecomingAFolderCannotGrantTraversalOrPruneOnAnIncompleteGeneration() {
        listing(file("document", false, "1"));
        files.put("remote", new GoogleDriveProvider.FileMetadata("remote", "Remote", "text/plain", "1",
                null, null, false, List.of(), null, null));
        links.put("document", List.of(new GoogleDriveLinkReader.Link(link("remote"), "Paragraph 1")));
        var configuration = scheduleConfiguration();
        configuration.discoverLinkedDocuments(scheduleOwner, source, 2);
        replaceAndActivate(configuration, scheduleOwner, 2, List.of(link("folder")), List.of("remote"));
        finish(enqueue());
        files.put("remote", file("remote", true, "2"));
        files.put("unapproved-descendant", child("unapproved-descendant", false, "remote"));
        pages.put("remote:first", new GoogleDriveProvider.FilePage(List.of(files.get("unapproved-descendant")), null));
        listing();
        var operation = enqueue();
        finish(operation);
        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.FAILED);
        verify(session, never()).listFiles("remote", null);
        verify(session, never()).metadata("unapproved-descendant");
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE status='DELETING'")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM connector_items")).isEqualTo(2);
    }

    @Test
    void candidateFailuresAndFreshMetadataCannotBeTurnedIntoArbitraryApprovals() {
        listing(file("document", false, "1"));
        files.put("remote", new GoogleDriveProvider.FileMetadata("remote", "Remote", "text/plain", "1",
                null, null, false, List.of(), null, null));
        files.put("linked-folder", file("linked-folder", true, "1"));
        files.put("unsupported-type", new GoogleDriveProvider.FileMetadata("unsupported-type", "Image", "image/png",
                "1", null, null, false, List.of(), null, null));
        links.put("document", List.of(
                new GoogleDriveLinkReader.Link(link("remote"), "A1"), new GoogleDriveLinkReader.Link(link("linked-folder"), "A2"),
                new GoogleDriveLinkReader.Link(link("missing"), "A3"), new GoogleDriveLinkReader.Link(link("unsupported-type"), "A4")));
        var configuration = scheduleConfiguration();
        var discovered = configuration.discoverLinkedDocuments(scheduleOwner, source, 2);
        assertThat(discovered.discoveryErrors()).contains(new GoogleDriveSourceService.DiscoveryError("missing", "missing", "SOURCE_GOOGLE_NOT_FOUND"));
        for (String id : List.of("arbitrary", "linked-folder", "missing", "unsupported-type")) {
            org.junit.jupiter.api.Assertions.assertThrows(SourceException.class,
                    () -> replaceAndActivate(configuration, scheduleOwner, 2, List.of(link("folder")), List.of(id)));
        }
        files.remove("remote");
        var failed = finishSelection(configuration, submitSelection(configuration, scheduleOwner, 2, ScopeMode.SPECIFIC, List.of(link("document")), List.of("remote")));
        assertThat(failed.status()).isEqualTo(SourceOperationStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo("SOURCE_GOOGLE_NOT_FOUND");
        assertThat(roots.configuration(tenant, source).revision()).isEqualTo(2);
        assertThat(roots.approvedIds(tenant, source)).isEmpty();
        assertThat(roots.roots(tenant, source)).extracting(GoogleDriveSourceService.Root::id).containsExactly("folder");
    }

    @ParameterizedTest
    @ValueSource(strings = {"content", "targets", "edges", "operations"})
    void discoveryBoundsFailExplicitlyAndKeepThePreviousCompleteSnapshot(String bound) {
        listing(file("document", false, "1"));
        links.put("document", List.of(new GoogleDriveLinkReader.Link(link("document"), "Self")));
        var configuration = scheduleConfiguration();
        var previous = configuration.discoverLinkedDocuments(scheduleOwner, source, 2);
        switch (bound) {
            case "content" -> listing(java.util.stream.IntStream.range(0, 101).mapToObj(i -> file("input" + i, false, "1"))
                    .toArray(GoogleDriveProvider.FileMetadata[]::new));
            case "targets" -> links.put("document", java.util.stream.IntStream.range(0, 501)
                    .mapToObj(i -> new GoogleDriveLinkReader.Link(link("target" + i), "Cell " + i)).toList());
            case "edges" -> links.put("document", java.util.stream.IntStream.range(0, 2001)
                    .mapToObj(i -> new GoogleDriveLinkReader.Link(link("document"), "Cell " + i)).toList());
            case "operations" -> {
                for (int i = 0; i < 513; i++) pages.put(i == 0 ? "first" : "page" + i,
                        new GoogleDriveProvider.FilePage(List.of(), i == 512 ? null : "page" + (i + 1)));
            }
            default -> throw new AssertionError(bound);
        }
        var failure = org.junit.jupiter.api.Assertions.assertThrows(GoogleDriveProviderException.class,
                () -> configuration.discoverLinkedDocuments(scheduleOwner, source, 2));
        assertThat(failure.failure()).isEqualTo(GoogleDriveProviderException.Failure.LIMIT_EXCEEDED);
        assertThat(configuration.configuration(scheduleOwner, source)).isEqualTo(previous);
    }

    private GoogleDriveSourceService scheduleConfiguration() {
        return new DefaultGoogleDriveSourceService(authorization, connections, roots, sources, syncRows, attempts, mappings, linkReader, manager, new JdbcGoogleDriveSelectionRepository(jdbc), credentials, new GoogleDriveSelectionPolicy(1000, 3145728), new JdbcSourceGroupRepository(jdbc));
    }

    private SourceOperationView finishSelection(GoogleDriveSourceService configuration, GoogleDriveSourceService.SelectionReceipt receipt) {
        var rows = new JdbcGoogleDriveSelectionRepository(jdbc);
        var processor = new DefaultGoogleDriveSelectionProcessor(rows, (DefaultGoogleDriveSourceService) configuration, connections, manager);
        for (int batch = 0; batch < 256; batch++) {
            var state = rows.find(tenant, receipt.operation().id()).orElseThrow();
            if (state.status() != SourceOperationStatus.NOT_STARTED && state.status() != SourceOperationStatus.IN_PROGRESS) return state;
            var delivery = dispatch.claim(OperationWorkload.GOOGLE_DRIVE_SELECTION_VALIDATION, 1).getFirst().delivery();
            processor.execute(processor.claim(tenant, receipt.operation().id(), delivery.deliveryId()).orElseThrow());
        }
        throw new AssertionError("Selection did not terminate within fixture budget");
    }

    private GoogleDriveSourceService.SelectionReceipt submitSelection(GoogleDriveSourceService configuration,
            io.memoryos.iam.ActorId actor, long revision, ScopeMode mode, List<String> links, List<String> approvals) {
        var draft = configuration.selectionDraft(actor, source);
        return configuration.replaceRoots(actor, UUID.randomUUID(), source, revision,
                draft.discoveryRevision(), draft.credentialRevision(), mode, links, approvals);
    }

    private void replaceAndActivate(GoogleDriveSourceService configuration, io.memoryos.iam.ActorId actor,
            long scopeRevision, List<String> links, List<String> approvals) {
        var receipt = submitSelection(configuration, actor, scopeRevision, ScopeMode.SPECIFIC, links, approvals);
        assertThat(finishSelection(configuration, receipt).status()).isEqualTo(SourceOperationStatus.SUCCEEDED);
    }

    private List<GoogleDriveSourceService.SelectionItem> selected(GoogleDriveSourceService configuration,
            GoogleDriveSourceService.SelectionKind kind) {
        return configuration.selection(scheduleOwner, source, null, kind, null, 100).items();
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
                    new JdbcDocumentRepository(jdbc, mapper, _ -> { }), extractor, storage, mock(StoredObjectRegistry.class), tx,
                    scheduler, new DefaultExtractionArtifactService(new JdbcExtractionArtifactRepository(jdbc), storage, mapper),
                    registry, new SourceSyncProcessor(service(), scheduler, registry),
                    mock(io.memoryos.ingestion.application.SelectionValidationProcessor.class));
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
            return syncRows.enqueue(tenant, source, revision.get(), SourceRunTrigger.MANUAL, scheduleOwner).id();
        }));
    }

    private ConnectorSyncPort.Work claim(SourceOperationId operation) {
        jdbc.sql("UPDATE source_sync_attempts SET next_dispatch_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=:id").param("id", operation.value()).update();
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
