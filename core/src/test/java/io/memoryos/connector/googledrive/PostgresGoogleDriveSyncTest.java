package io.memoryos.connector.googledrive;

import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.googledrive.persistence.GoogleDriveCredentialConfiguration;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveAclRepository;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveCredentialRepository;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveSelectionRepository;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveSourceRepository;
import io.memoryos.connector.sharepoint.SharePointConnectionService;
import io.memoryos.connector.source.SourceAccessPolicy;
import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.source.persistence.JdbcSourceGroupRepository;
import io.memoryos.connector.source.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.source.persistence.JdbcSourceQueryRepository;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.connector.sync.SourceSyncEngine;
import io.memoryos.connector.sync.persistence.SyncTarget;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveSyncRepository;
import io.memoryos.connector.sync.ProviderAuthorityService;
import io.memoryos.connector.sync.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository;
import io.memoryos.iam.group.DefaultGroupScopeService;
import io.memoryos.iam.group.persistence.GroupInvariantRepository;
import io.memoryos.iam.group.persistence.GroupProjectionRepository;
import io.memoryos.ingestion.application.SelectionValidationProcessor;
import io.memoryos.shared.ActorId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.*;
import io.memoryos.connector.GoogleDriveSourceService.ScopeMode;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.DocumentId;
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
import io.memoryos.shared.TenantId;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    private JdbcGoogleDriveSyncRepository googleRows;
    private JdbcGoogleDriveCredentialRepository credentials;
    private JdbcIndexAttemptRepository attempts;
    private GoogleDriveConnectionService connections;
    private final GoogleGroupSynchronizer groupSynchronizer = mock(GoogleGroupSynchronizer.class);
    private GoogleDriveProvider.Session session;
    private ObjectWriteService writes;
    private OperationDispatchPort dispatch;
    private TenantId tenant;
    private SourceId source;
    private IamAuthorization authorization;
    private final ActorId scheduleOwner = new ActorId(UUID.randomUUID());
    private final AtomicLong revision = new AtomicLong(1);
    private final Map<String, GoogleDriveProvider.FileMetadata> files = new HashMap<>();
    private final Map<String, GoogleDriveProvider.FilePage> pages = new HashMap<>();
    private final Map<ObjectKey, byte[]> bytes = new HashMap<>();
    private final Map<String, byte[]> contents = new HashMap<>();
    private final Map<ObjectKey, ObjectMetadata> metadata = new HashMap<>();
    private final Set<String> unsupported = new HashSet<>();
    private final Set<String> failing = new HashSet<>();
    private final List<String> calls = new ArrayList<>();
    private CredentialId credentialId;
    private final GoogleDriveLinkReader linkReader = mock(GoogleDriveLinkReader.class);
    private final Map<String, List<GoogleDriveLinkReader.Link>> links = new HashMap<>();
    private ObjectStorage storage;
    private Runnable duringExtraction = () -> {};
    private final List<Object> published = new ArrayList<>();

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
        jdbc.sql("INSERT INTO iam_group_capability_grants(tenant_id,group_id,capability) VALUES (:tenant,:tenant,'SYSTEM_ADMIN')")
                .param("tenant", tenant.value()).update();
        jdbc.sql("INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id) VALUES (:tenant,:tenant,:actor)")
                .param("tenant", tenant.value()).param("actor", scheduleOwner.value()).update();
        authorization = new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), new IamLockRepository(jdbc));
        sources = new JdbcSourceRepository(jdbc, event -> { });
        var pair = tx.execute(_ -> sources.createFileSource(tenant, scheduleOwner, "Drive", SourceAccess.PRIVATE, scheduleOwner));
        source = Objects.requireNonNull(pair).sourceId();
        jdbc.sql("UPDATE connectors SET connector_type='GOOGLE_DRIVE' WHERE id=:id").param("id", pair.connectorId()).update();
        jdbc.sql("UPDATE connector_credential_pairs SET access_type='PRIVATE' WHERE id=:id").param("id", source.value()).update();
        items = new JdbcSourceItemRepository(jdbc);
        mappings = new JdbcSourceDocumentRepository(jdbc);
        roots = new JdbcGoogleDriveSourceRepository(jdbc);
        syncRows = new JdbcSourceSyncRepository(jdbc);
        googleRows = new JdbcGoogleDriveSyncRepository(jdbc, syncRows);
        credentials = new JdbcGoogleDriveCredentialRepository(jdbc, sources,
                new GoogleDriveCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "test"),
                mappings, syncRows);
        try (var client = new GoogleDriveOAuthClient("fixture.apps.googleusercontent.com", "fixture-secret".getBytes(StandardCharsets.UTF_8));
                var grant = new GoogleDriveAuthorizationService.Grant("fixture-subject", "fixture@example.test",
                        GoogleDriveAuthorizationService.REQUIRED_SCOPES, "fixture-refresh".getBytes(StandardCharsets.UTF_8))) {
            credentialId = Objects.requireNonNull(tx.execute(_ -> credentials.create(tenant, scheduleOwner, "Fixture credential", grant, client)));
        }
        jdbc.sql("UPDATE connector_credential_pairs SET credential_id=:credential WHERE id=:source")
                .param("credential", credentialId.value()).param("source", source.value()).update();
        session = mock(GoogleDriveProvider.Session.class);
        connections = mock(GoogleDriveConnectionService.class);
        when(connections.current(any(), any(), anyLong())).thenAnswer(i -> (long) i.getArgument(2) == revision.get());
        when(connections.state(any(), any())).thenAnswer(_ -> new GoogleDriveConnectionService.State(credentialId, "owner@example.test", "ACTIVE", revision.get(), true, "OAUTH"));
        when(connections.open(any(), any())).thenAnswer(_ -> new GoogleDriveConnectionService.Connection(session, revision.get()));
        when(connections.openCredential(any(), any())).thenAnswer(_ -> new GoogleDriveConnectionService.Connection(session, revision.get()));
        when(linkReader.read(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            GoogleDriveProvider.AcquiredContent content = invocation.getArgument(0);
            return links.getOrDefault(content.descriptor().providerFileId(), List.of());
        });
        attempts = new JdbcIndexAttemptRepository(jdbc, sources, mappings,
                new ProviderAuthorityService(connections, mock(SharePointConnectionService.class)));
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
                public InputStream inputStream() { return stream; }
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
        when(session.permissions(any())).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(permission("owner", "owner"));
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
            if (failing.contains(file.id())) throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.MALFORMED);
            return new GoogleDriveProvider.AcquiredContent(file.name(), "text/plain",
                    contents.getOrDefault(file.id(), (file.id() + ":" + file.version()).getBytes(StandardCharsets.UTF_8)),
                    new SourceInputDescriptor(SourceInputFormat.BINARY, file.id(), file.version(), "https://drive.google.com/file/d/" + file.id() + "/view"));
        });
    }

    @Test
    void anItemThatFailsIsAnErrorOfACompletedRunAndTheNextRunResolvesIt() {
        listing(file("document", false, "1"), file("bad", false, "1"));
        failing.add("bad");
        var first = enqueue();
        finish(first);

        assertThat(attemptStatus(first)).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(scalar("SELECT COUNT(*) FROM connector_item_versions WHERE provider_file_id='document'")).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT code FROM source_run_errors
                WHERE run_id = :run AND error_key = 'FILE:bad' AND resolved_at IS NULL AND stage = 'PROVIDER'
                """).param("run", first.value()).query(String.class).single()).isEqualTo("SOURCE_GOOGLE_MALFORMED");
        assertThat(roots.configuration(tenant, source).errorCode()).isNull();

        failing.clear();
        var second = enqueue();
        finish(second);

        assertThat(attemptStatus(second)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("""
                SELECT resolved_by_run_id FROM source_run_errors
                WHERE run_id = :run AND error_key = 'FILE:bad' AND resolved_at IS NOT NULL
                """).param("run", first.value()).query(UUID.class).single()).isEqualTo(second.value());
    }

    @Test
    void pausingOrDeletingTheSourceCancelsItsRunningSync() {
        listing(file("document", false, "1"));
        var paused = enqueue();
        var work = claim(paused);
        jdbc.sql("UPDATE connector_credential_pairs SET status = 'PAUSED' WHERE id = :id")
                .param("id", source.value()).update();

        assertThat(service().execute(work)).isEqualTo(ConnectorSyncPort.Result.CANCELLED);
        assertThat(attemptStatus(paused)).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT error_code FROM source_sync_attempts WHERE id = :id")
                .param("id", paused.value()).query(String.class).single()).isEqualTo("SOURCE_PAUSED");

        jdbc.sql("UPDATE connector_credential_pairs SET status = 'ACTIVE' WHERE id = :id")
                .param("id", source.value()).update();
        var deleted = enqueue();
        var deletion = claim(deleted);
        jdbc.sql("UPDATE connector_credential_pairs SET status = 'DELETING' WHERE id = :id")
                .param("id", source.value()).update();

        assertThat(service().execute(deletion)).isEqualTo(ConnectorSyncPort.Result.CANCELLED);
        assertThat(jdbc.sql("SELECT status || ':' || error_code FROM source_sync_attempts WHERE id = :id")
                .param("id", deleted.value()).query(String.class).single()).isEqualTo("CANCELLED:SOURCE_DELETING");
        verify(session, never()).acquire(any());
    }

    @Test
    void aFileBeneathAFolderThisRunListedFindsItsRootWithoutAskingDriveAgain() {
        files.put("sub", child("sub", true, "folder"));
        files.put("deep", child("deep", false, "sub"));
        listing(files.get("sub"));
        pages.put("sub:first", new GoogleDriveProvider.FilePage(List.of(files.get("deep")), null));

        finish(enqueue());

        assertThat(scalar("SELECT COUNT(*) FROM connector_item_versions WHERE provider_file_id='deep'")).isEqualTo(1);
        // sub is read as a file, checked for a stable version while its sharing is read, and read again as a
        // folder; deep's walk to its root answers from the membership this run recorded for sub.
        assertThat(calls.stream().filter("metadata:sub"::equals).count()).isEqualTo(3);
    }

    @Test
    void resumingPausedGoogleDriveSyncContinuesWithCurrentSchema() {
        SourceOperationId paused = enqueue();
        jdbc.sql("""
                UPDATE source_sync_attempts
                SET status = 'CANCELLED', error_code = 'SOURCE_PAUSED', phase = 'SCAN'
                WHERE id = :id
                """).param("id", paused.value()).update();
        jdbc.sql("""
                INSERT INTO google_drive_frontier (tenant_id, attempt_id, file_id, task_kind)
                VALUES (:tenant, :attempt, 'folder', 'FOLDER')
                """).param("tenant", tenant.value()).param("attempt", paused.value()).update();

        SourceOperationView resumed = googleRows.enqueueResumed(tenant, source, revision.get(), scheduleOwner).orElseThrow();

        assertThat(resumed.id()).isNotEqualTo(paused);
        assertThat(resumed.status()).isEqualTo(SourceOperationStatus.NOT_STARTED);
        assertThat(jdbc.sql("""
                SELECT trigger_kind FROM source_sync_attempts WHERE id = :id
                """).param("id", resumed.id().value()).query(String.class).single()).isEqualTo("RESUMED");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM google_drive_frontier WHERE attempt_id = :id
                """).param("id", resumed.id().value()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void serviceAccountSourcesAdvanceGroupMembershipAlongsideTheirSyncSteps() {
        listing(file("one", false, "1"));
        finish(enqueue());
        verify(groupSynchronizer, never()).advance(any(), any(), anyLong(), any(), any());

        when(connections.state(any(), any())).thenAnswer(_ -> new GoogleDriveConnectionService.State(credentialId,
                "admin@example.test", "ACTIVE", revision.get(), false, "SERVICE_ACCOUNT"));
        when(groupSynchronizer.advance(any(), any(), anyLong(), any(), any())).thenReturn(true, true, false);
        finish(enqueue());

        verify(groupSynchronizer, Mockito.atLeast(3)).advance(tenant, credentialId, revision.get(),
                "admin@example.test", session);
    }

    @Test
    void permissionOnlyChangesRefreshTheSnapshotWithoutAcquiringOrIndexingContent() {
        listing(file("one", false, "1"));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        var acls = new JdbcGoogleDriveAclRepository(jdbc, event -> {});
        var first = acls.read(tenant, source, "one").orElseThrow();
        when(session.permissions("one")).thenReturn(List.of(permission("reader", "reader")));
        Mockito.clearInvocations(session);

        finish(enqueue());

        var updated = acls.read(tenant, source, "one").orElseThrow();
        assertThat(updated.revision()).isEqualTo(first.revision() + 1);
        assertThat(updated.permissions()).extracting(GoogleDriveProvider.Permission::id).containsExactly("reader");
        assertThat(updated.documentIds()).isEqualTo(first.documentIds());
        assertThat(updated.contextStatus()).isEqualTo(GoogleDriveAclSnapshot.ContextStatus.CURRENT);
        verify(session, never()).acquire(any());
        assertThat(dispatch.claim(OperationWorkload.INGESTION, 1)).isEmpty();
    }

    @Test
    void failedAclRefreshRetainsTheCompleteSnapshotWithoutBlockingUnchangedContent() {
        listing(file("one", false, "1"));
        finish(enqueue());
        var acls = new JdbcGoogleDriveAclRepository(jdbc, event -> {});
        var first = acls.read(tenant, source, "one").orElseThrow();
        when(session.permissions("one")).thenThrow(new GoogleDriveProviderException(
                GoogleDriveProviderException.Failure.MALFORMED));
        Mockito.clearInvocations(session);

        finish(enqueue());

        var failed = acls.read(tenant, source, "one").orElseThrow();
        assertThat(failed.status()).isEqualTo(GoogleDriveAclSnapshot.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("SOURCE_GOOGLE_MALFORMED");
        assertThat(failed.revision()).isEqualTo(first.revision());
        assertThat(failed.permissions()).isEqualTo(first.permissions());
        assertThat(failed.lastSuccess()).isEqualTo(first.lastSuccess());
        verify(session, never()).acquire(any());
    }

    @Test
    void metadataVersionBumpWithIdenticalBinaryBytesRefreshesTheAclAndReusesExtraction() {
        byte[] content = "unchanged binary content".getBytes(StandardCharsets.UTF_8);
        doAnswer(i -> {
            GoogleDriveProvider.FileMetadata file = i.getArgument(0);
            return new GoogleDriveProvider.AcquiredContent(file.name(), "text/plain", content,
                    new SourceInputDescriptor(SourceInputFormat.BINARY, file.id(), file.version(),
                            "https://drive.google.com/file/d/" + file.id() + "/view"));
        }).when(session).acquire(any());
        listing(file("one", false, "1"));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        var before = new JdbcGoogleDriveAclRepository(jdbc, event -> {}).read(tenant, source, "one").orElseThrow();
        listing(file("one", false, "2"));
        when(session.permissions("one")).thenReturn(List.of(permission("reader", "reader")));

        finish(enqueue());

        assertThat(dispatch.claim(OperationWorkload.INGESTION, 1)).isEmpty();
        assertThat(scalar("SELECT COUNT(*) FROM connector_item_versions")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM object_writes WHERE status='ADOPTED'")).isEqualTo(1);
        var after = new JdbcGoogleDriveAclRepository(jdbc, event -> {}).read(tenant, source, "one").orElseThrow();
        assertThat(after.documentIds()).isEqualTo(before.documentIds());
        assertThat(after.permissions()).extracting(GoogleDriveProvider.Permission::id).containsExactly("reader");
        Mockito.clearInvocations(session);
        finish(enqueue());
        verify(session, never()).acquire(any());
        assertThat(jdbc.sql("SELECT provider_version FROM connector_item_versions").query(String.class).single()).isEqualTo("2");
    }

    @Test
    void credentialRevisionChangeDuringAclRetrievalCannotPublishTheNewSnapshot() {
        listing(file("one", false, "1"));
        finish(enqueue());
        var acls = new JdbcGoogleDriveAclRepository(jdbc, event -> {});
        var before = acls.read(tenant, source, "one").orElseThrow();
        when(session.permissions("one")).thenAnswer(_ -> {
            revokeCredentialRevision();
            return List.of(permission("reader", "reader"));
        });

        assertThat(service().execute(claim(enqueue()))).isEqualTo(ConnectorSyncPort.Result.SUPERSEDED);
        var after = acls.read(tenant, source, "one").orElseThrow();
        assertThat(after.revision()).isEqualTo(before.revision());
        assertThat(after.permissions()).isEqualTo(before.permissions());
    }

    @Test
    void roleChangesAndAddedPermissionsPublishWhileIdenticalObservationsOnlyAdvanceTheRevision() {
        listing(file("one", false, "1"));
        when(session.permissions("one")).thenReturn(List.of(permission("shared", "reader")));
        finish(enqueue());
        var acls = new JdbcGoogleDriveAclRepository(jdbc, event -> {});
        var first = acls.read(tenant, source, "one").orElseThrow();

        published.clear();
        when(session.permissions("one")).thenReturn(List.of(permission("shared", "writer")));
        finish(enqueue());

        var promoted = acls.read(tenant, source, "one").orElseThrow();
        assertThat(promoted.revision()).isEqualTo(first.revision() + 1);
        assertThat(promoted.permissions()).extracting(GoogleDriveProvider.Permission::role).containsExactly("writer");
        assertThat(aclChanges()).extracting(GoogleDriveAclChanged::revision).containsExactly(promoted.revision());

        published.clear();
        when(session.permissions("one")).thenReturn(List.of(permission("shared", "writer"), permission("added", "commenter")));
        finish(enqueue());

        var added = acls.read(tenant, source, "one").orElseThrow();
        assertThat(added.permissions()).extracting(GoogleDriveProvider.Permission::id).containsExactly("shared", "added");
        assertThat(aclChanges()).extracting(GoogleDriveAclChanged::revision).containsExactly(added.revision());

        published.clear();
        finish(enqueue());

        assertThat(acls.read(tenant, source, "one").orElseThrow().revision()).isEqualTo(added.revision() + 1);
        assertThat(aclChanges()).isEmpty();
    }

    @Test
    void missingOAuthScopeStopsTheRunAndRequiresReconnectInsteadOfMarkingFilesUnavailable() {
        listing(file("one", false, "1"));
        finish(enqueue());
        when(session.permissions("one")).thenThrow(new GoogleDriveProviderException(
                GoogleDriveProviderException.Failure.SCOPE_INSUFFICIENT));

        var operation = enqueue();
        finish(operation);

        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.FAILED);
        assertThat(jdbc.sql("SELECT error_code FROM source_sync_attempts WHERE id=:id").param("id", operation.value())
                .query(String.class).single()).isEqualTo("SOURCE_GOOGLE_SCOPE_INSUFFICIENT");
        verify(connections).authenticationFailed(ArgumentMatchers.eq(tenant),
                ArgumentMatchers.eq(source), anyLong());
        var snapshot = new JdbcGoogleDriveAclRepository(jdbc, event -> {}).read(tenant, source, "one").orElseThrow();
        assertThat(snapshot.errorCode()).isEqualTo("SOURCE_GOOGLE_SCOPE_INSUFFICIENT");
        assertThat(scalar("SELECT COUNT(*) FROM google_drive_frontier WHERE error_code='SOURCE_GOOGLE_NOT_FOUND'")).isZero();
    }

    @Test
    void unreadableSharingRecordsAccessDeniedWithoutBlockingContent() {
        listing(file("one", false, "1"));
        when(session.permissions("one")).thenThrow(new GoogleDriveProviderException(
                GoogleDriveProviderException.Failure.ACCESS_DENIED));

        var operation = enqueue();
        finish(operation);

        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.SUCCEEDED);
        var snapshot = new JdbcGoogleDriveAclRepository(jdbc, event -> {}).read(tenant, source, "one").orElseThrow();
        assertThat(snapshot.status()).isEqualTo(GoogleDriveAclSnapshot.Status.FAILED);
        assertThat(snapshot.errorCode()).isEqualTo("SOURCE_GOOGLE_ACCESS_DENIED");
        assertThat(snapshot.revision()).isZero();
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        verify(connections, never()).authenticationFailed(any(), any(), anyLong());
    }

    @Test
    void readByDocumentReturnsTheMappedFileSnapshotOnlyWithinItsTenant() {
        listing(file("one", false, "1"));
        when(session.permissions("one")).thenReturn(List.of(permission("shared", "reader")));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        var acls = new JdbcGoogleDriveAclRepository(jdbc, event -> {});
        var snapshot = acls.read(tenant, source, "one").orElseThrow();
        DocumentId document = snapshot.documentIds().getFirst();

        assertThat(acls.readByDocument(tenant, document)).singleElement().satisfies(found -> {
            assertThat(found.sourceId()).isEqualTo(source);
            assertThat(found.fileId()).isEqualTo("one");
            assertThat(found.revision()).isEqualTo(snapshot.revision());
            assertThat(found.permissions()).isEqualTo(snapshot.permissions());
            assertThat(found.documentIds()).containsExactly(document);
        });
        assertThat(acls.readByDocument(new TenantId(UUID.randomUUID()), document)).isEmpty();
        assertThat(acls.readByDocument(tenant, new DocumentId(UUID.randomUUID()))).isEmpty();
    }

    private List<GoogleDriveAclChanged> aclChanges() {
        return published.stream().filter(GoogleDriveAclChanged.class::isInstance)
                .map(GoogleDriveAclChanged.class::cast).toList();
    }

    private static GoogleDriveProvider.Permission permission(String id, String role) {
        return new GoogleDriveProvider.Permission(id, "user", role, id + "@example.test",
                null, null, null, false, false, List.of(), null, null);
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

        assertThat(attemptStatus(incomplete)).isEqualTo("COMPLETED_WITH_ERRORS");
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
        assertThat(attemptStatus(incomplete)).isEqualTo("COMPLETED_WITH_ERRORS");
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
    void selectionShrinkKeepsIndexedRootsDescendantsAndRetainedApprovedLinksReady() {
        jdbc.sql("UPDATE connector_credential_pairs SET access_type='PUBLIC' WHERE id=:source")
                .param("source", source.value()).update();
        listing(file("descendant", false, "1"));
        for (String id : List.of("direct", "removed-root", "first-link", "second-link")) {
            files.put(id, new GoogleDriveProvider.FileMetadata(id, id + ".txt", "text/plain", "1",
                    null, null, false, List.of(), null, null));
        }
        var configuration = scheduleConfiguration();
        replaceAndActivate(configuration, scheduleOwner, 2,
                List.of(link("folder"), link("direct"), link("removed-root")), List.of());
        links.put("direct", List.of(new GoogleDriveLinkReader.Link(link("first-link"), "Paragraph 1"),
                new GoogleDriveLinkReader.Link(link("second-link"), "Paragraph 2")));
        configuration.discoverLinkedDocuments(scheduleOwner, source, 3);
        replaceAndActivate(configuration, scheduleOwner, 3,
                List.of(link("folder"), link("direct"), link("removed-root")), List.of("first-link", "second-link"));
        finish(enqueue());
        for (int i = 0; i < 5; i++) assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        jdbc.sql("UPDATE documents SET searchable_generation=content_generation").update();
        var queries = new JdbcSourceQueryRepository(jdbc);
        var ready = queries.items(tenant, source, null, 100).items().stream()
                .filter(item -> Set.of("descendant.txt", "direct.txt", "second-link.txt").contains(item.filename())).toList();
        assertThat(ready).hasSize(3).allSatisfy(item -> {
            assertThat(item.searchStatus()).isEqualTo("READY");
            assertThat(item.lastIndexedAt()).isNotNull();
        });
        var retainedDocuments = jdbc.sql("""
                SELECT m.document_id FROM documents_by_connector_credential_pair m
                JOIN connector_items i ON i.tenant_id=m.tenant_id AND i.id=m.connector_item_id
                WHERE i.provider_file_id IN ('descendant','direct','second-link')
                """).query(UUID.class).list();
        var removedDocuments = jdbc.sql("""
                SELECT m.document_id FROM documents_by_connector_credential_pair m
                JOIN connector_items i ON i.tenant_id=m.tenant_id AND i.id=m.connector_item_id
                WHERE i.provider_file_id IN ('removed-root','first-link')
                """).query(UUID.class).list();
        assertThat(mappings.readableDocuments(tenant, scheduleOwner, retainedDocuments)).containsExactlyInAnyOrderElementsOf(retainedDocuments);
        var documentsBefore = jdbc.sql("SELECT to_jsonb(d)::text FROM documents d ORDER BY id").query(String.class).list();
        var versionsBefore = jdbc.sql("SELECT current_version_id FROM connector_items ORDER BY id").query(UUID.class).list();
        var mappingTimesBefore = jdbc.sql("""
                SELECT last_indexed_at FROM documents_by_connector_credential_pair
                WHERE document_id IN (:documents) ORDER BY document_id
                """).param("documents", retainedDocuments).query(OffsetDateTime.class).list();
        var oldSync = claim(enqueue());
        tx.executeWithoutResult(_ -> {
            sources.lock(tenant, source);
            googleRows.start(oldSync);
        });

        replaceAndActivate(configuration, scheduleOwner, 4,
                List.of(link("folder"), link("direct")), List.of("second-link"));

        ready.forEach(item -> assertThat(queries.item(tenant, source, item.id())).isEqualTo(item));
        assertThat(mappings.readableDocuments(tenant, scheduleOwner, retainedDocuments)).containsExactlyInAnyOrderElementsOf(retainedDocuments);
        assertThat(mappings.readableDocuments(tenant, scheduleOwner, removedDocuments)).isEmpty();
        assertThat(jdbc.sql("""
                SELECT last_indexed_at FROM documents_by_connector_credential_pair
                WHERE document_id IN (:documents) ORDER BY document_id
                """).param("documents", retainedDocuments).query(OffsetDateTime.class).list()).isEqualTo(mappingTimesBefore);
        assertThat(service().execute(oldSync)).isEqualTo(ConnectorSyncPort.Result.SUPERSEDED);
        Mockito.clearInvocations(session);
        var next = enqueue();
        finish(next);

        assertThat(syncRows.find(tenant, next).orElseThrow().status()).isEqualTo(SourceOperationStatus.SUCCEEDED);
        ready.forEach(item -> assertThat(queries.item(tenant, source, item.id())).isEqualTo(item));
        assertThat(jdbc.sql("SELECT to_jsonb(d)::text FROM documents d ORDER BY id").query(String.class).list()).isEqualTo(documentsBefore);
        assertThat(jdbc.sql("SELECT current_version_id FROM connector_items ORDER BY id").query(UUID.class).list()).isEqualTo(versionsBefore);
        assertThat(scalar("SELECT COUNT(*) FROM index_attempts")).isEqualTo(5);
        assertThat(mappings.readableDocuments(tenant, scheduleOwner, retainedDocuments)).containsExactlyInAnyOrderElementsOf(retainedDocuments);
        assertThat(mappings.readableDocuments(tenant, scheduleOwner, removedDocuments)).isEmpty();
        verify(session, never()).acquire(any());
    }

    @Test
    void selectionEditCancelsPendingInputWithoutLosingItsPreviousPublishedDocumentOrStrandingRetry() {
        jdbc.sql("UPDATE connector_credential_pairs SET access_type='PUBLIC' WHERE id=:source")
                .param("source", source.value()).update();
        listing(file("document", false, "1"));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        var document = new DocumentId(jdbc.sql("SELECT id FROM documents").query(UUID.class).single());
        var publishedBefore = jdbc.sql("SELECT to_jsonb(d)::text FROM documents d").query(String.class).single();
        listing(file("document", false, "2"));
        finish(enqueue());
        var pendingVersion = jdbc.sql("SELECT current_version_id FROM connector_items").query(UUID.class).single();
        duringExtraction = () -> replaceAndActivate(scheduleConfiguration(), scheduleOwner, 2, List.of(link("folder")), List.of());

        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.SKIPPED);
        duringExtraction = () -> {};
        assertThat(mappings.hasEligibleMapping(tenant, scheduleOwner, document)).isTrue();
        assertThat(jdbc.sql("SELECT to_jsonb(d)::text FROM documents d").query(String.class).single()).isEqualTo(publishedBefore);
        assertThat(scalar("SELECT scope_revision FROM connector_item_versions WHERE id='" + pendingVersion + "'")).isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM index_attempts WHERE status='CANCELLED'")).isEqualTo(1);

        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        assertThat(jdbc.sql("SELECT current_version_id FROM connector_items").query(UUID.class).single()).isNotEqualTo(pendingVersion);
        assertThat(jdbc.sql("SELECT metadata_json FROM documents").query(String.class).single()).contains("document:2");
        assertThat(mappings.hasEligibleMapping(tenant, scheduleOwner, document)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"credential", "scope", "membership", "mapping", "excluded"})
    void selectionEditDoesNotRestoreStaleOrRevokedIndexedAuthority(String revoked) {
        jdbc.sql("UPDATE connector_credential_pairs SET access_type='PUBLIC' WHERE id=:source")
                .param("source", source.value()).update();
        listing(file("document", false, "1"));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        var document = new DocumentId(jdbc.sql("SELECT id FROM documents").query(UUID.class).single());
        var version = jdbc.sql("SELECT current_version_id FROM connector_items").query(UUID.class).single();
        switch (revoked) {
            case "credential" -> {
                jdbc.sql("UPDATE google_drive_credentials SET credential_revision=credential_revision+1").update();
                revision.incrementAndGet();
            }
            case "scope" -> jdbc.sql("UPDATE connector_item_versions SET scope_revision=1").update();
            case "membership" -> jdbc.sql("UPDATE google_drive_membership SET eligible=FALSE, root_id=NULL").update();
            case "mapping" -> mappings.invalidateSource(tenant, source);
            case "excluded" -> jdbc.sql("UPDATE google_drive_membership SET excluded=TRUE").update();
            default -> throw new AssertionError(revoked);
        }

        replaceAndActivate(scheduleConfiguration(), scheduleOwner, 2, List.of(link("folder")), List.of());

        assertThat(mappings.hasEligibleMapping(tenant, scheduleOwner, document)).isFalse();
        assertThat(attempts.canReplay(tenant, source, version)).isFalse();
        assertThat(scalar("SELECT COUNT(*) FROM connector_item_versions WHERE scope_revision=3")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM index_attempts")).isEqualTo(1);
    }

    @Test
    void rejectsOverlappingAndUnsupportedRootsWithoutChangingTheAcceptedScope() {
        var owner = scheduleOwner;
        var configuration = new DefaultGoogleDriveSourceService(authorization, connections, roots, sources, syncRows, attempts, Mockito.mock(GoogleDriveLinkReader.class), manager, new JdbcGoogleDriveSelectionRepository(jdbc), credentials, new GoogleDriveSelectionPolicy(1000, 3145728), new JdbcSourceGroupRepository(jdbc, event -> { }), new SourceAccessPolicy(authorization, sources, new DefaultGroupScopeService(new GroupInvariantRepository(jdbc), new GroupProjectionRepository(jdbc)), TestDatabase.noAudit()), new GoogleDriveMetadataCache(), TestDatabase.noAudit());
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
        var stale = Assertions.assertThrows(SourceException.class,
                () -> replaceAndActivate(configuration, owner, 2, List.of(link("folder")), List.of()));
        assertThat(stale.code()).isEqualTo("SOURCE_GOOGLE_REVISION_CONFLICT");
        assertThat(selected(configuration, GoogleDriveSourceService.SelectionKind.FILE))
                .extracting(GoogleDriveSourceService.SelectionItem::id).containsExactly("document");
    }

    @Test
    void fileSearchIncludesFilesSynchronizedUnderSelectedFolders() {
        listing(file("document", false, "1"));
        finish(enqueue());

        var files = scheduleConfiguration().selection(scheduleOwner, source, "document",
                GoogleDriveSourceService.SelectionKind.FILE, null, 100).items();

        assertThat(files).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("document");
            assertThat(item.name()).isEqualTo("document.txt");
            assertThat(item.selected()).isFalse();
            assertThat(item.coveredByRoots()).isTrue();
        });
    }


    @Test
    void rejectsDuplicateLinksAndWholeDriveRootsWithoutChangingSelection() {
        var owner = scheduleOwner;
        var configuration = new DefaultGoogleDriveSourceService(authorization, connections, roots, sources, syncRows, attempts, Mockito.mock(GoogleDriveLinkReader.class), manager, new JdbcGoogleDriveSelectionRepository(jdbc), credentials, new GoogleDriveSelectionPolicy(1000, 3145728), new JdbcSourceGroupRepository(jdbc, event -> { }), new SourceAccessPolicy(authorization, sources, new DefaultGroupScopeService(new GroupInvariantRepository(jdbc), new GroupProjectionRepository(jdbc)), TestDatabase.noAudit()), new GoogleDriveMetadataCache(), TestDatabase.noAudit());
        Assertions.assertThrows(SourceException.class,
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
        tx.executeWithoutResult(_ -> roots.replace(tenant, source, 2, revision.get(), GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(
                new GoogleDriveSourceService.Root("my-drive", "My Drive", "application/vnd.google-apps.folder"),
                new GoogleDriveSourceService.Root("workspace-drive", "Team drive", "application/vnd.google-apps.folder")), List.of()));

        var operation = enqueue();
        finish(operation);

        assertThat(attemptStatus(operation)).isEqualTo("COMPLETED_WITH_ERRORS");
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
        var configuration = new DefaultGoogleDriveSourceService(authorization, connections, roots, sources, syncRows, attempts, Mockito.mock(GoogleDriveLinkReader.class), manager, new JdbcGoogleDriveSelectionRepository(jdbc), credentials, new GoogleDriveSelectionPolicy(1000, 3145728), new JdbcSourceGroupRepository(jdbc, event -> { }), new SourceAccessPolicy(authorization, sources, new DefaultGroupScopeService(new GroupInvariantRepository(jdbc), new GroupProjectionRepository(jdbc)), TestDatabase.noAudit()), new GoogleDriveMetadataCache(), TestDatabase.noAudit());
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
        var configuration = new DefaultGoogleDriveSourceService(authorization, connections, roots, sources, syncRows, attempts, Mockito.mock(GoogleDriveLinkReader.class), manager, new JdbcGoogleDriveSelectionRepository(jdbc), credentials, new GoogleDriveSelectionPolicy(1000, 3145728), new JdbcSourceGroupRepository(jdbc, event -> { }), new SourceAccessPolicy(authorization, sources, new DefaultGroupScopeService(new GroupInvariantRepository(jdbc), new GroupProjectionRepository(jdbc)), TestDatabase.noAudit()), new GoogleDriveMetadataCache(), TestDatabase.noAudit());
        jdbc.sql("DELETE FROM google_drive_roots").update();
        Assertions.assertThrows(SourceException.class,
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
        assertThat(attemptStatus(operation)).isEqualTo("COMPLETED_WITH_ERRORS");
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
            googleRows.start(syncWork);
        });
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.SKIPPED);
        duringExtraction = () -> {};
        service().execute(syncWork);
        finish(operation);
        var delivery = dispatch.claim(OperationWorkload.INGESTION, 1).getFirst().delivery();
        var indexing = TestDatabase.transactionalProxy(attempts, ConnectorIndexingPort.class, manager);
        var retryWork = indexing.claim(tenant, delivery.operationId(), delivery.deliveryId()).orElseThrow();
        assertThat(indexing.retry(retryWork, "SOURCE_EXTRACTION_FAILED", null, null, 2, Duration.ofSeconds(1))).isTrue();
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
        assertThat(attemptStatus(operation)).isEqualTo("COMPLETED_WITH_ERRORS");
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
        assertThat(mappings.hasEligibleMapping(tenant, scheduleOwner, new DocumentId(document))).isFalse();
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
            roots.replace(tenant, source, 2, revision.get(), GoogleDriveSourceService.ScopeMode.SPECIFIC, List.of(new GoogleDriveSourceService.Root("document", "document.txt", "text/plain")), List.of());
            syncRows.supersede(tenant, source);
        });
        assertThat(service().execute(old)).isEqualTo(ConnectorSyncPort.Result.SUPERSEDED);
        var item = new SourceItemId(jdbc.sql("SELECT id FROM connector_items").query(UUID.class).single());
        tx.executeWithoutResult(_ -> googleRows.exclude(tenant, source, item));
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
        tx.executeWithoutResult(_ -> syncRows.supersede(tenant, source));
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
    void versionOnlyChangeWithIdenticalContentStaysUnchangedAndKeepsTheIndexedDocument() {
        // Sharing or metadata edits advance the Drive version without changing the file bytes.
        contents.put("document", "Quarterly report".getBytes(StandardCharsets.UTF_8));
        listing(file("document", false, "1"));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        var documentsBefore = jdbc.sql("SELECT row_to_json(d)::text FROM documents d").query(String.class).list();

        listing(file("document", false, "2"));
        var operation = enqueue();
        finish(operation);

        assertThat(syncRows.find(tenant, operation).orElseThrow().status()).isEqualTo(SourceOperationStatus.SUCCEEDED);
        assertThat(jdbc.sql("SELECT provider_version FROM connector_item_versions").query(String.class).list()).containsExactly("2");
        assertThat(scalar("SELECT COUNT(*) FROM index_attempts")).isEqualTo(1);
        assertThat(scalar("SELECT unchanged FROM source_sync_attempts WHERE id='" + operation.value() + "'")).isEqualTo(1);
        assertThat(scalar("SELECT acquired FROM source_sync_attempts WHERE id='" + operation.value() + "'")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM object_writes WHERE status='ADOPTED'")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM documents_by_connector_credential_pair WHERE retrieval_eligible")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT row_to_json(d)::text FROM documents d").query(String.class).list()).isEqualTo(documentsBefore);

        // The refreshed provider version lets the next run skip the download entirely.
        finish(enqueue());
        verify(session, times(2)).acquire(any());
    }

    @Test
    void contentChangeKeepsThePreviousDocumentRetrievableWhenTheNewVersionFails() {
        listing(file("document", false, "1"));
        finish(enqueue());
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        var generation = jdbc.sql("SELECT content_generation::text FROM documents").query(String.class).single();

        listing(file("document", false, "2"));
        finish(enqueue());
        assertThat(scalar("SELECT COUNT(*) FROM connector_item_versions")).isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM documents_by_connector_credential_pair WHERE retrieval_eligible")).isEqualTo(1);

        var delivery = dispatch.claim(OperationWorkload.INGESTION, 1).getFirst().delivery();
        var indexing = TestDatabase.transactionalProxy(attempts, ConnectorIndexingPort.class, manager);
        var work = indexing.claim(tenant, delivery.operationId(), delivery.deliveryId()).orElseThrow();
        assertThat(indexing.fail(work, "SOURCE_EXTRACTION_FAILED", null, null)).isTrue();

        assertThat(scalar("SELECT COUNT(*) FROM documents_by_connector_credential_pair WHERE retrieval_eligible")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT content_generation::text FROM documents WHERE status='ELIGIBLE'").query(String.class).single())
                .isEqualTo(generation);
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
    void isolatedItemFailureCompletesWithErrorsAndReschedulesUsingTheSavedInterval() {
        scheduleConfiguration().updateSchedule(scheduleOwner, source, 1, 19);
        listing(file("bad", false, "1"));
        unsupported.add("bad");
        var operation = enqueue();

        finish(operation);

        assertThat(attemptStatus(operation)).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(roots.configuration(tenant, source).errorCode()).isNull();
        assertThat(jdbc.sql("SELECT code FROM source_run_errors WHERE run_id = :run AND error_key = 'FILE:bad'")
                .param("run", operation.value()).query(String.class).single()).isEqualTo("SOURCE_GOOGLE_UNSUPPORTED");
        assertScheduledFrom(operation, "completed_at", 19);
    }

    @Test
    void retryExhaustionUsesAnIntervalSavedBetweenAttemptsWithoutChangingRetryBackoff() {
        var operation = enqueue();
        when(connections.open(any(), any())).thenThrow(
                new GoogleDriveProviderException(GoogleDriveProviderException.Failure.UNAVAILABLE));
        for (int attempt = 0; attempt < 6; attempt++) {
            boolean exhausts = attempt == 5;
            tx.executeWithoutResult(_ -> {
                assertThat(service().execute(claim(operation))).isEqualTo(ConnectorSyncPort.Result.FAILED);
                // The last failure exhausts the budget and dispatches nothing more.
                if (!exhausts) assertThat(jdbc.sql("""
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
        Mockito.clearInvocations(linkReader);
        finish(configuration.synchronize(scheduleOwner, source).id());
        assertThat(jdbc.sql("SELECT provider_file_id FROM connector_items").query(String.class).list())
                .containsExactlyInAnyOrder("left", "right", "remote");
        for (int i = 0; i < 3; i++) assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        assertThat(scalar("SELECT COUNT(*) FROM documents")).isEqualTo(3);
        Mockito.verifyNoInteractions(linkReader);

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
        Mockito.clearInvocations(linkReader);
        jdbc.sql("UPDATE google_drive_sources SET next_sync_at=CURRENT_TIMESTAMP - INTERVAL '1 minute'").update();
        assertThat(service().enqueueDue(10)).isEqualTo(1);
        finish(new SourceOperationId(jdbc.sql("SELECT id FROM source_sync_attempts WHERE status='NOT_STARTED'").query(UUID.class).single()));
        Mockito.verifyNoInteractions(linkReader);
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
        assertThat(index(false)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        Mockito.clearInvocations(session);
        replaceAndActivate(configuration, scheduleOwner, 3, List.of(link("folder")), List.of());
        finish(enqueue());
        assertThat(scalar("SELECT COUNT(*) FROM connector_items WHERE status='DELETING'")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM google_drive_membership WHERE file_id='document' AND eligible AND NOT excluded")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM index_attempts")).isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM connector_item_versions")).isEqualTo(1);
        verify(session, never()).acquire(any());
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
        assertThat(attemptStatus(operation)).isEqualTo("COMPLETED_WITH_ERRORS");
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
            Assertions.assertThrows(SourceException.class,
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
            case "content" -> listing(IntStream.range(0, 101).mapToObj(i -> file("input" + i, false, "1"))
                    .toArray(GoogleDriveProvider.FileMetadata[]::new));
            case "targets" -> links.put("document", IntStream.range(0, 501)
                    .mapToObj(i -> new GoogleDriveLinkReader.Link(link("target" + i), "Cell " + i)).toList());
            case "edges" -> links.put("document", IntStream.range(0, 2001)
                    .mapToObj(i -> new GoogleDriveLinkReader.Link(link("document"), "Cell " + i)).toList());
            case "operations" -> {
                for (int i = 0; i < 513; i++) pages.put(i == 0 ? "first" : "page" + i,
                        new GoogleDriveProvider.FilePage(List.of(), i == 512 ? null : "page" + (i + 1)));
            }
            default -> throw new AssertionError(bound);
        }
        var failure = Assertions.assertThrows(GoogleDriveProviderException.class,
                () -> configuration.discoverLinkedDocuments(scheduleOwner, source, 2));
        assertThat(failure.failure()).isEqualTo(GoogleDriveProviderException.Failure.LIMIT_EXCEEDED);
        assertThat(configuration.configuration(scheduleOwner, source)).isEqualTo(previous);
    }

    private GoogleDriveSourceService scheduleConfiguration() {
        return new DefaultGoogleDriveSourceService(authorization, connections, roots, sources, syncRows, attempts, linkReader, manager, new JdbcGoogleDriveSelectionRepository(jdbc), credentials, new GoogleDriveSelectionPolicy(1000, 3145728), new JdbcSourceGroupRepository(jdbc, event -> { }), new SourceAccessPolicy(authorization, sources, new DefaultGroupScopeService(new GroupInvariantRepository(jdbc), new GroupProjectionRepository(jdbc)), TestDatabase.noAudit()), new GoogleDriveMetadataCache(), TestDatabase.noAudit());
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
            ActorId actor, long revision, ScopeMode mode, List<String> links, List<String> approvals) {
        var draft = configuration.selectionDraft(actor, source);
        return configuration.replaceRoots(actor, UUID.randomUUID(), source, revision,
                draft.discoveryRevision(), draft.credentialRevision(), mode, links, approvals);
    }

    private void replaceAndActivate(GoogleDriveSourceService configuration, ActorId actor,
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
                if (revokeDuringExtraction) revokeCredentialRevision();
                return new DocumentContent("text/plain", name, text, Map.of("content", text));
            } catch (IOException exception) { throw new IllegalStateException(exception); }
        };
        var indexing = TestDatabase.transactionalProxy(attempts, ConnectorIndexingPort.class, manager);
        var registry = new SimpleMeterRegistry();
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var coordinator = new DefaultIngestionCoordinator(indexing, mock(ConnectorCleanupPort.class),
                    new JdbcDocumentRepository(jdbc, mapper, _ -> { }), extractor, storage, mock(StoredObjectRegistry.class), tx,
                    scheduler, new DefaultExtractionArtifactService(new JdbcExtractionArtifactRepository(jdbc), storage, mapper),
                    registry, new SourceSyncProcessor(service(), scheduler, registry),
                    mock(SelectionValidationProcessor.class));
            return coordinator.process(delivery);
        } finally {
            registry.close();
        }
    }

    private String attemptStatus(SourceOperationId operation) {
        return jdbc.sql("SELECT status FROM source_sync_attempts WHERE id = :id")
                .param("id", operation.value()).query(String.class).single();
    }

    /** A newer credential revision, as reconnecting the account records it. */
    private void revokeCredentialRevision() {
        jdbc.sql("UPDATE google_drive_credentials SET credential_revision = credential_revision + 1").update();
        revision.incrementAndGet();
    }

    private SourceSyncEngine service() {
        return new SourceSyncEngine(syncRows, sources, items, attempts, mappings, writes,
                List.of(new GoogleDriveSyncTraversal(googleRows, roots,
                        new JdbcGoogleDriveAclRepository(jdbc, published::add), connections, groupSynchronizer)),
                manager);
    }

    private SourceOperationId enqueue() {
        return Objects.requireNonNull(tx.execute(_ -> {
            sources.lock(tenant, source);
            return syncRows.enqueue(SyncTarget.GOOGLE_DRIVE, tenant, source, revision.get(), SourceRunTrigger.MANUAL, scheduleOwner).id();
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
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
