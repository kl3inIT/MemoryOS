package io.memoryos.connector.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.*;
import io.memoryos.connector.persistence.*;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.application.DefaultExtractionArtifactService;
import io.memoryos.document.persistence.JdbcDocumentRepository;
import io.memoryos.document.persistence.JdbcExtractionArtifactRepository;
import io.memoryos.iam.ActorId;
import io.memoryos.ingestion.*;
import io.memoryos.ingestion.application.DefaultIngestionCoordinator;
import io.memoryos.ingestion.application.SelectionValidationProcessor;
import io.memoryos.ingestion.application.SourceSyncProcessor;
import io.memoryos.ingestion.persistence.JdbcOperationDispatchRepository;
import io.memoryos.objectstorage.*;
import io.memoryos.objectstorage.application.DefaultObjectWriteService;
import io.memoryos.objectstorage.application.ObjectUploadProperties;
import io.memoryos.objectstorage.persistence.JdbcObjectWriteRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import io.memoryos.iam.IamException;
import io.memoryos.iam.application.DefaultIamAuthorization;
import io.memoryos.iam.persistence.IamAuthorizationRepository;
import io.memoryos.iam.persistence.IamLockRepository;
import io.memoryos.iam.TenantId;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
class PostgresSourceRunHistoryTest {
    private HikariDataSource ds;

    @AfterEach
    void closeDatabase() {
        if (ds != null) {
            ds.close();
        }
    }

    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private DataSourceTransactionManager manager;
    private JdbcSourceRepository sources;
    private JdbcSourceItemRepository items;
    private JdbcSourceDocumentRepository mappings;
    private JdbcSourceSyncRepository sync;
    private JdbcIndexAttemptRepository attempts;
    private JdbcSourceRunHistoryRepository queries;
    private SourceRunHistoryService history;
    private DefaultConnectorSyncService service;
    private OperationDispatchPort dispatch;
    private ObjectStorage storage;
    private TenantId tenant;
    private SourceId source;
    private final ActorId owner = new ActorId(UUID.randomUUID());
    private final Map<String, GoogleDriveProvider.FileMetadata> files = new HashMap<>();
    private List<GoogleDriveProvider.FileMetadata> listing = List.of();
    private final Map<ObjectKey, byte[]> bytes = new HashMap<>();
    private final Map<ObjectKey, ObjectMetadata> metadata = new HashMap<>();
    private boolean extractionFails;

    @BeforeEach
    void initialize() throws Exception {
        ds = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(ds);
        manager = new DataSourceTransactionManager(ds);
        tx = new TransactionTemplate(manager);
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES (:id,'history','History','ACTIVE','HISTORY-TEST')")
                .param("id", tenant.value()).update();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", owner.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES (:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant",tenant.value()).param("actor",owner.value()).update();
        jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name,system_key) VALUES (:tenant,:tenant,'Admin','ADMIN')")
                .param("tenant", tenant.value()).update();
        jdbc.sql("INSERT INTO iam_group_capability_grants(tenant_id,group_id,capability) VALUES (:tenant,:tenant,'IAM_ADMIN')")
                .param("tenant", tenant.value()).update();
        jdbc.sql("INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id) VALUES (:tenant,:tenant,:actor)")
                .param("tenant", tenant.value()).param("actor", owner.value()).update();
        sources = new JdbcSourceRepository(jdbc);
        var pair = Objects.requireNonNull(tx.execute(_ -> sources.createFileSource(tenant, "History")));
        source = pair.sourceId();
        jdbc.sql("UPDATE connectors SET connector_type='GOOGLE_DRIVE' WHERE id=:id").param("id", pair.connectorId()).update();
        jdbc.sql("UPDATE connector_credential_pairs SET access_type='RESTRICTED' WHERE id=:id").param("id", source.value()).update();
        jdbc.sql("INSERT INTO google_drive_sources(tenant_id,source_id,scope_mode) VALUES (:tenant,:source,'SPECIFIC')")
                .param("tenant", tenant.value()).param("source", source.value()).update();
        jdbc.sql("INSERT INTO google_drive_roots(tenant_id,source_id,file_id,name,mime_type) VALUES (:tenant,:source,'folder','Folder','application/vnd.google-apps.folder')")
                .param("tenant", tenant.value()).param("source", source.value()).update();
        files.put("folder", new GoogleDriveProvider.FileMetadata("folder", "Folder", "application/vnd.google-apps.folder", "1",
                null, null, false, List.of("my-drive"), null, null));
        var session = mock(GoogleDriveProvider.Session.class);
        when(session.metadata(any())).thenAnswer(call -> {
            var file = files.get(call.getArgument(0));
            if (file == null) throw new GoogleDriveProviderException(GoogleDriveProviderException.Failure.NOT_FOUND);
            return file;
        });
        when(session.listFiles(any(), any())).thenAnswer(_ -> new GoogleDriveProvider.FilePage(listing, null));
        when(session.acquire(any())).thenAnswer(call -> {
            GoogleDriveProvider.FileMetadata file = call.getArgument(0);
            return new GoogleDriveProvider.AcquiredContent(file.name(), "text/plain", (file.id() + ":" + file.version()).getBytes(StandardCharsets.UTF_8),
                    new SourceInputDescriptor(SourceInputFormat.BINARY, file.id(), file.version(), "https://drive.google.com/file/d/" + file.id() + "/view"));
        });
        var connections = mock(GoogleDriveConnectionService.class);
        when(connections.current(any(), any(), anyLong())).thenReturn(true);
        when(connections.open(any(), any())).thenReturn(new GoogleDriveConnectionService.Connection(session, 1));
        items = new JdbcSourceItemRepository(jdbc);
        mappings = new JdbcSourceDocumentRepository(jdbc);
        sync = new JdbcSourceSyncRepository(jdbc);
        attempts = new JdbcIndexAttemptRepository(jdbc, sources, mappings, connections);
        storage = mock(ObjectStorage.class);
        doAnswer(call -> {
            ObjectKey key = call.getArgument(0);
            byte[] value = call.getArgument(1);
            bytes.put(key, value);
            metadata.put(key, new ObjectMetadata(value.length, call.getArgument(2), checksum(value)));
            return null;
        }).when(storage).write(any(), any(), any());
        when(storage.inspect(any())).thenAnswer(call -> metadata.get(call.getArgument(0)));
        when(storage.open(any())).thenAnswer(call -> {
            ObjectKey key = call.getArgument(0);
            var stream = new ByteArrayInputStream(bytes.get(key));
            return new ObjectContent() {
                public ObjectMetadata metadata() { return metadata.get(key); }
                public java.io.InputStream inputStream() { return stream; }
                public void close() {}
            };
        });
        var writes = new DefaultObjectWriteService(new JdbcStoredObjectRepository(jdbc), new JdbcObjectWriteRepository(jdbc), storage,
                new ObjectUploadProperties(Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(1), 16), manager);
        service = new DefaultConnectorSyncService(sync, sources, new JdbcGoogleDriveSourceRepository(jdbc), items, attempts,
                mappings, connections, writes, manager);
        dispatch = TestDatabase.transactionalProxy(new JdbcOperationDispatchRepository(jdbc), OperationDispatchPort.class, manager);
        queries = new JdbcSourceRunHistoryRepository(jdbc);
        history = new DefaultSourceRunHistoryService(queries, new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), new IamLockRepository(jdbc)), new JdbcSourceQueryRepository(jdbc));
    }

    @Test
    void acquisitionCompletionDoesNotStealEarlierIndexingAndDuplicateDeliveryCannotDoubleCountPublication() {
        list(file("one", "1"));
        var first = finish(enqueue());
        assertThat(sync.find(tenant, new SourceOperationId(first.id())).orElseThrow().status()).isEqualTo(SourceOperationStatus.SUCCEEDED);
        assertThat(first.status()).isEqualTo(SourceRunStatus.INDEXING);
        assertThat(first.completedAt()).isNull();
        assertThat(first.counts().scanned()).isEqualTo(1);
        assertThat(first.counts().acquired()).isEqualTo(1);
        var second = finish(enqueue());
        assertThat(second.status()).isEqualTo(SourceRunStatus.SUCCEEDED);
        assertThat(second.indexingStatus()).isEqualTo(SourceRunIndexingStatus.NOT_REQUIRED);
        assertThat(second.counts().unchanged()).isEqualTo(1);
        assertThat(second.counts().alreadyPending()).isEqualTo(1);
        assertThat(second.counts().acquired()).isZero();
        var delivery = dispatch.claim(OperationWorkload.INGESTION, 1).getFirst().delivery();
        assertThat(process(delivery)).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        assertThat(process(delivery)).isEqualTo(IngestionCoordinator.Outcome.SKIPPED);
        assertThat(run(first.id()).counts().published()).isEqualTo(1);
        assertThat(run(first.id()).completedAt()).isNotNull();
        assertThat(run(second.id()).counts().published()).isZero();
        assertThat(run(second.id()).completedAt()).isEqualTo(second.completedAt());
    }

    @Test
    void terminalItemErrorsAndBulkCancellationSurviveItemCleanupWithoutRewritingHistory() {
        list(file("bad", "1"), file("cancel", "1"));
        var run = finish(enqueue());
        extractionFails = true;
        var delivery = dispatch.claim(OperationWorkload.INGESTION, 1).getFirst().delivery();
        assertThat(process(delivery)).isEqualTo(IngestionCoordinator.Outcome.FAILED);
        tx.executeWithoutResult(_ -> attempts.cancelForSource(tenant, source));
        tx.executeWithoutResult(_ -> attempts.cancelForSource(tenant, source));
        var completed = run(run.id());
        assertThat(completed.counts().indexingFailed()).isEqualTo(1);
        assertThat(completed.counts().indexingCancelled()).isEqualTo(1);
        assertThat(completed.counts().indexingPending()).isZero();
        assertThat(completed.status()).isEqualTo(SourceRunStatus.COMPLETED_WITH_ERRORS);
        var errors = history.errors(owner, source, run.id(), null, 25).items();
        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error.stage()).isEqualTo(SourceRunErrorStage.EXTRACTION);
            assertThat(error.code()).isEqualTo("SOURCE_EXTRACTION_MALFORMED");
            assertThat(error.fileName()).isNotBlank();
        });
        var cleanup = new JdbcCleanupAttemptRepository(jdbc);
        for (UUID item : jdbc.sql("SELECT id FROM connector_items WHERE tenant_id=:tenant").param("tenant", tenant.value()).query(UUID.class).list()) {
            tx.executeWithoutResult(_ -> cleanup.removeItemRows(new CleanupWork(new SourceOperationId(UUID.randomUUID()), tenant,
                    SourceOperationType.REMOVE_ITEM, source, new SourceItemId(item), UUID.randomUUID(), null)));
        }
        assertThat(run(run.id()).counts()).isEqualTo(completed.counts());
        assertThat(history.errors(owner, source, run.id(), null, 25).items()).isEqualTo(errors);
    }

    @Test
    void reconciliationDeferralDoesNotConsumeRetryBudgetAndExpiredClaimsAreRecoveryPending() {
        list(file("one", "1"));
        var run = finish(enqueue());
        var first = claimIndex();
        assertThat(attempts.claim(tenant, first.operationId(), UUID.randomUUID())).isEmpty();
        jdbc.sql("UPDATE google_drive_membership SET eligible=FALSE WHERE source_id=:source").param("source", source.value()).update();
        tx.executeWithoutResult(_ -> attempts.supersede(first));
        assertThat(run(run.id()).counts().indexingPending()).isEqualTo(1);
        assertThat(run(run.id()).counts().indexingSuperseded()).isZero();
        jdbc.sql("UPDATE google_drive_membership SET eligible=TRUE WHERE source_id=:source").param("source", source.value()).update();
        var retried = claimIndex();
        tx.executeWithoutResult(_ -> attempts.retry(retried, "SOURCE_STORAGE_READ_UNAVAILABLE", 2, Duration.ofSeconds(1)));
        assertThat(run(run.id()).indexingStatus()).isEqualTo(SourceRunIndexingStatus.RETRY_SCHEDULED);
        assertThat(run(run.id()).counts().indexingFailed()).isZero();
        jdbc.sql("UPDATE index_attempts SET next_dispatch_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=:id").param("id", retried.operationId().value()).update();
        var exhausted = claimIndex();
        jdbc.sql("UPDATE index_attempts SET lease_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=:id")
                .param("id", exhausted.operationId().value()).update();
        assertThat(run(run.id()).indexingStatus()).isEqualTo(SourceRunIndexingStatus.RECOVERY_PENDING);
        jdbc.sql("UPDATE index_attempts SET lease_expires_at=CURRENT_TIMESTAMP+INTERVAL '1 minute' WHERE id=:id")
                .param("id", exhausted.operationId().value()).update();
        tx.executeWithoutResult(_ -> attempts.retry(exhausted, "SOURCE_STORAGE_READ_UNAVAILABLE", 2, Duration.ofSeconds(1)));
        assertThat(run(run.id()).counts().indexingFailed()).isEqualTo(1);
        assertThat(run(run.id()).completedAt()).isNotNull();
    }

    @Test
    void failedStorageAcquisitionRecordsSafeStageWithoutPruningEarlierDocuments() {
        list(file("keep", "1"));
        finish(enqueue());
        assertThat(process(dispatch.claim(OperationWorkload.INGESTION, 1).getFirst().delivery())).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        list(file("new", "1"));
        doThrow(new ObjectStorageException(ObjectStorageFailureCode.UNAVAILABLE, true,
                new SSLException("private-host-and-token-must-not-escape"))).when(storage).write(any(), any(), any());
        var failed = finish(enqueue());
        assertThat(failed.status()).isEqualTo(SourceRunStatus.FAILED);
        assertThat(failed.counts().scanned()).isEqualTo(1);
        assertThat(failed.counts().acquisitionFailed()).isEqualTo(1);
        assertThat(failed.counts().removed()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM documents WHERE tenant_id=:tenant").param("tenant", tenant.value()).query(Long.class).single()).isEqualTo(1);
        assertThat(history.errors(owner, source, failed.id(), null, 25).items()).anySatisfy(error -> {
            assertThat(error.stage()).isEqualTo(SourceRunErrorStage.STORAGE_WRITE);
            assertThat(error.code()).isEqualTo("SOURCE_STORAGE_WRITE_TLS");
            assertThat(error.fileName()).isEqualTo("new.txt");
        }).noneSatisfy(error -> assertThat(error.code()).contains("private"));
    }

    @Test
    void stableBoundedPagesRemainTenantSourceAndFilterScopedAndLegacyFactsStayUnknown() {
        var oldest = finish(enqueue());
        var middle = finish(enqueue());
        var newest = finish(enqueue());
        var page = history.list(owner, source, query(null, 2));
        assertThat(page.items()).extracting(SourceRun::id).containsExactly(newest.id(), middle.id());
        assertThat(page.totalItems()).isEqualTo(3);
        var later = finish(enqueue());
        var next = history.list(owner, source, query(page.nextCursor(), 2));
        assertThat(next.items()).extracting(SourceRun::id).containsExactly(oldest.id());
        assertThat(next.totalItems()).isEqualTo(4);
        assertThat(history.list(owner, source, query(null, 2)).lastSuccessful().id()).isEqualTo(later.id());
        assertThatThrownBy(() -> history.list(owner, source, new SourceRunHistoryService.Query(page.nextCursor(), 2,
                SourceRunStatus.FAILED, null, null, null))).isInstanceOf(SourceException.class);
        var foreign = Objects.requireNonNull(tx.execute(_ -> sources.createFileSource(tenant, "Other"))).sourceId();
        assertThatThrownBy(() -> history.list(owner, foreign, query(page.nextCursor(), 2))).isInstanceOf(SourceException.class);
        assertThat(history.list(owner, foreign, query(null, 2)).totalItems()).isZero();
        assertThat(queries.list(new TenantId(UUID.randomUUID()), source, query(null, 2)).totalItems()).isZero();
        assertThatThrownBy(() -> history.get(owner, foreign, oldest.id())).isInstanceOf(SourceException.class);
        assertThatThrownBy(() -> history.get(new ActorId(UUID.randomUUID()), source, oldest.id())).isInstanceOf(IamException.class);
        assertThatThrownBy(() -> history.list(owner, source, query(null, 101))).isInstanceOf(SourceException.class);
        UUID legacy = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO source_sync_attempts(id,tenant_id,source_id,scope_revision,credential_revision,generation,status,completed_at)
                VALUES (:id,:tenant,:source,1,1,0,'SUCCEEDED',CURRENT_TIMESTAMP)
                """).param("id", legacy).param("tenant", tenant.value()).param("source", source.value()).update();
        var unknown = history.get(owner, source, legacy);
        assertThat(unknown.trigger()).isNull();
        assertThat(unknown.status()).isEqualTo(SourceRunStatus.UNKNOWN);
        assertThat(unknown.acquisitionStatus()).isEqualTo(SourceRunStatus.SUCCEEDED);
        assertThat(unknown.indexingStatus()).isEqualTo(SourceRunIndexingStatus.UNKNOWN);
        assertThat(unknown.counts().scanned()).isNull();
        assertThat(unknown.counts().published()).isNull();
        assertThat(unknown.completedAt()).isNull();
    }

    @Test
    void totalsUseDerivedStatusTriggerAndHalfOpenDatesWithoutCursorRestrictions() {
        var before = finish(enqueue());
        var oldest = finish(enqueue());
        var newest = finish(enqueue());
        var scheduled = finish(enqueue());
        list(file("pending", "1"));
        var indexing = finish(enqueue());
        var after = finish(enqueue());
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        var runs = List.of(before, oldest, newest, scheduled, indexing, after);
        for (int index = 0; index < runs.size(); index++) {
            jdbc.sql("UPDATE source_sync_attempts SET created_at = :created WHERE id = :id")
                    .param("created", java.sql.Timestamp.from(start.plusSeconds(index)))
                    .param("id", runs.get(index).id()).update();
        }
        jdbc.sql("UPDATE source_sync_attempts SET trigger_kind = 'SCHEDULED' WHERE id = :id")
                .param("id", scheduled.id()).update();
        var first = history.list(owner, source, new SourceRunHistoryService.Query(null, 1,
                SourceRunStatus.SUCCEEDED, SourceRunTrigger.MANUAL, start.plusSeconds(1), start.plusSeconds(5)));
        assertThat(first.items()).extracting(SourceRun::id).containsExactly(newest.id());
        assertThat(first.totalItems()).isEqualTo(2);
        var secondQuery = new SourceRunHistoryService.Query(first.nextCursor(), 1,
                SourceRunStatus.SUCCEEDED, SourceRunTrigger.MANUAL, start.plusSeconds(1), start.plusSeconds(5));
        var second = history.list(owner, source, secondQuery);
        assertThat(second.items()).extracting(SourceRun::id).containsExactly(oldest.id());
        assertThat(second.totalItems()).isEqualTo(2);
        assertThat(second.nextCursor()).isNull();
        assertThat(second.current().id()).isEqualTo(indexing.id());
        assertThat(second.lastSuccessful().id()).isEqualTo(after.id());
        jdbc.sql("UPDATE source_sync_attempts SET status = 'FAILED' WHERE id = :id")
                .param("id", oldest.id()).update();
        var exhausted = history.list(owner, source, secondQuery);
        assertThat(exhausted.items()).isEmpty();
        assertThat(exhausted.totalItems()).isEqualTo(1);
    }

    @Test
    void retentionCompactsTerminalDetailsButRetainsCurrentInputsAndUnresolvedErrors() {
        list(file("one", "1"));
        var first = finish(enqueue());
        assertThat(process(dispatch.claim(OperationWorkload.INGESTION, 1).getFirst().delivery())).isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        var before = run(first.id()).counts();
        jdbc.sql("UPDATE source_sync_attempts SET run_completed_at=CURRENT_TIMESTAMP-INTERVAL '100 days' WHERE id=:id")
                .param("id", first.id()).update();
        var retention = new JdbcSourceRunRetentionRepository(jdbc);
        tx.executeWithoutResult(_ -> retention.prune(Instant.now().minus(Duration.ofDays(90)), Instant.now().minus(Duration.ofDays(14)), 25));
        assertThat(run(first.id()).detailsExpired()).isTrue();
        assertThat(run(first.id()).counts()).isEqualTo(before);
        assertThat(history.list(owner, source, query(null, 1)).totalItems()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM index_attempts WHERE source_sync_attempt_id=:id").param("id", first.id()).query(Long.class).single()).isEqualTo(1);
        list(file("one", "2"));
        var failed = finish(enqueue());
        extractionFails = true;
        process(dispatch.claim(OperationWorkload.INGESTION, 1).getFirst().delivery());
        jdbc.sql("UPDATE source_sync_attempts SET run_completed_at=CURRENT_TIMESTAMP-INTERVAL '100 days' WHERE id=:id")
                .param("id", failed.id()).update();
        tx.executeWithoutResult(_ -> retention.prune(Instant.now().minus(Duration.ofDays(90)), Instant.now().minus(Duration.ofDays(14)), 25));
        assertThatThrownBy(() -> run(first.id())).isInstanceOf(SourceException.class);
        assertThat(run(failed.id()).detailsExpired()).isFalse();
        assertThat(history.errors(owner, source, failed.id(), null, 25).items()).hasSize(1);
        assertThat(history.list(owner, source, query(null, 1)).totalItems()).isEqualTo(1);
    }

    @Test
    void inactiveTenantBulkCancellationClosesOwnedChildrenOnlyOnce() {
        list(file("one", "1"), file("two", "1"));
        var run = finish(enqueue());
        jdbc.sql("UPDATE tenants SET status='INACTIVE' WHERE id=:id").param("id", tenant.value()).update();
        dispatch.cancelInactiveTenantIndexing(100);
        dispatch.cancelInactiveTenantIndexing(100);
        assertThat(queries.get(tenant, source, run.id()).counts().indexingCancelled()).isEqualTo(2);
        assertThat(queries.get(tenant, source, run.id()).counts().indexingPending()).isZero();
        assertThat(queries.get(tenant, source, run.id()).completedAt()).isNotNull();
    }

    @Test
    void trueSupersessionSettlesOnceAndManualReindexNeverReattributesClosedRun() {
        list(file("one", "1"));
        var first = finish(enqueue());
        var work = claimIndex();
        jdbc.sql("UPDATE google_drive_sources SET revision=revision+1 WHERE source_id=:source").param("source", source.value()).update();
        tx.executeWithoutResult(_ -> attempts.supersede(work));
        tx.executeWithoutResult(_ -> attempts.supersede(work));
        var settled = run(first.id());
        assertThat(settled.counts().indexingSuperseded()).isEqualTo(1);
        assertThat(settled.counts().indexingPending()).isZero();
        assertThat(settled.completedAt()).isNotNull();
        var manual = Objects.requireNonNull(tx.execute(_ -> {
            var pair = sources.lock(tenant, source);
            return attempts.create(tenant, pair, items.lockCurrentVersion(tenant, pair, work.itemId()));
        }));
        assertThat(jdbc.sql("SELECT source_sync_attempt_id IS NULL FROM index_attempts WHERE id=:id")
                .param("id", manual.id().value()).query(Boolean.class).single()).isTrue();
        tx.executeWithoutResult(_ -> attempts.cancelForSource(tenant, source));
        assertThat(run(first.id()).counts()).isEqualTo(settled.counts());
        assertThat(run(first.id()).completedAt()).isEqualTo(settled.completedAt());
    }

    @Test
    void completeReconciliationCountsRemovalOnceAndErrorsUseRunScopedKeysetPages() {
        list(file("one", "1"), file("two", "1"), file("three", "1"));
        var failed = finish(enqueue());
        extractionFails = true;
        for (int i = 0; i < 3; i++) process(dispatch.claim(OperationWorkload.INGESTION, 1).getFirst().delivery());
        var errors = history.errors(owner, source, failed.id(), null, 2);
        assertThat(errors.items()).hasSize(2);
        var next = history.errors(owner, source, failed.id(), errors.nextCursor(), 2);
        assertThat(next.items()).hasSize(1);
        assertThat(next.items().getFirst().id()).isNotIn(errors.items().stream().map(SourceRunError::id).toList());
        list();
        var removal = finish(enqueue());
        assertThat(removal.counts().removed()).isEqualTo(3);
        assertThat(removal.counts().scanned()).isZero();
        assertThat(finish(enqueue()).counts().removed()).isZero();
        assertThatThrownBy(() -> history.errors(owner, source, removal.id(), errors.nextCursor(), 2))
                .isInstanceOf(SourceException.class);
        assertThat(run(failed.id()).counts().indexingFailed()).isEqualTo(3);
    }

    @Test
    void predeploymentActiveRunResumesWithoutInventingHistoricalCounters() {
        list(file("one", "1"));
        UUID legacy = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO source_sync_attempts(id,tenant_id,source_id,scope_revision,credential_revision,generation)
                SELECT :id,tenant_id,source_id,revision,1,generation FROM google_drive_sources WHERE source_id=:source
                """).param("id", legacy).param("source", source.value()).update();
        var resumed = finish(new SourceOperationId(legacy));
        assertThat(resumed.counts().acquired()).isNull();
        assertThat(resumed.indexingStatus()).isEqualTo(SourceRunIndexingStatus.UNKNOWN);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM index_attempts WHERE source_sync_attempt_id=:run")
                .param("run", legacy).query(Long.class).single()).isEqualTo(1);
        assertThat(process(dispatch.claim(OperationWorkload.INGESTION, 1).getFirst().delivery()))
                .isEqualTo(IngestionCoordinator.Outcome.COMPLETED);
        assertThat(run(legacy).status()).isEqualTo(SourceRunStatus.UNKNOWN);
        assertThat(run(legacy).counts().published()).isNull();
        assertThat(run(legacy).completedAt()).isNull();
    }

    private SourceOperationId enqueue() {
        return Objects.requireNonNull(tx.execute(_ -> {
            sources.lock(tenant, source);
            return sync.enqueue(tenant, source, 1, SourceRunTrigger.MANUAL, owner).id();
        }));
    }

    private SourceRun finish(SourceOperationId id) {
        for (int step = 0; step < 20; step++) {
            if (sync.find(tenant, id).orElseThrow().status() == SourceOperationStatus.SUCCEEDED
                    || sync.find(tenant, id).orElseThrow().status() == SourceOperationStatus.FAILED) return run(id.value());
            jdbc.sql("UPDATE source_sync_attempts SET next_dispatch_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=:id").param("id", id.value()).update();
            var delivery = dispatch.claim(OperationWorkload.SOURCE_SYNC, 1).getFirst().delivery();
            service.execute(service.claim(tenant, id, delivery.deliveryId()).orElseThrow());
        }
        throw new AssertionError("fixture synchronization did not settle");
    }

    private IndexWork claimIndex() {
        var delivery = dispatch.claim(OperationWorkload.INGESTION, 1).getFirst().delivery();
        return Objects.requireNonNull(tx.execute(_ -> attempts.claim(tenant, delivery.operationId(), delivery.deliveryId()).orElseThrow()));
    }

    private IngestionCoordinator.Outcome process(OperationDelivery delivery) {
        var mapper = new ObjectMapper();
        SourceContentExtractor extractor = (input, size, name, descriptor) -> {
            if (extractionFails) throw new ExtractionException(ExtractionFailure.MALFORMED, "private diagnostics");
            return new DocumentContent("text/plain", name, "Extracted content", Map.of());
        };
        var indexing = TestDatabase.transactionalProxy(attempts, ConnectorIndexingPort.class, manager);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var coordinator = new DefaultIngestionCoordinator(indexing, mock(ConnectorCleanupPort.class),
                    new JdbcDocumentRepository(jdbc, mapper, _ -> { }), extractor, storage, mock(StoredObjectRegistry.class), tx,
                    scheduler, new DefaultExtractionArtifactService(new JdbcExtractionArtifactRepository(jdbc), storage, mapper),
                    registry, new SourceSyncProcessor(service, scheduler, registry), mock(SelectionValidationProcessor.class));
            return coordinator.process(delivery);
        } finally {
            registry.close();
        }
    }

    private SourceRun run(UUID id) { return history.get(owner, source, id); }
    private static SourceRunHistoryService.Query query(String cursor, int size) {
        return new SourceRunHistoryService.Query(cursor, size, null, null, null, null);
    }
    private void list(GoogleDriveProvider.FileMetadata... values) {
        listing = List.of(values);
        for (var file : values) files.put(file.id(), file);
    }
    private static GoogleDriveProvider.FileMetadata file(String id, String version) {
        return new GoogleDriveProvider.FileMetadata(id, id + ".txt", "text/plain", version, null, null, false, List.of("folder"), null, null);
    }
    private static ContentSha256 checksum(byte[] value) throws Exception {
        return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)));
    }
}
