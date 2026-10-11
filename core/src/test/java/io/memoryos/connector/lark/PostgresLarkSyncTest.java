package io.memoryos.connector.lark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.ConnectorSyncPort;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkException;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkGateway.ObjectType;
import io.memoryos.connector.LarkProviderException;
import io.memoryos.connector.LarkSourceService.RootKind;
import io.memoryos.connector.LarkSourceService.RootView;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceRunTrigger;
import io.memoryos.connector.SourceStatus;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.lark.persistence.JdbcLarkCredentialRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkSourceRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkSyncRepository;
import io.memoryos.connector.lark.persistence.LarkCredentialConfiguration;
import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.source.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.source.persistence.JdbcSourceQueryRepository;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.connector.sync.SourceSyncAdapters;
import io.memoryos.connector.sync.SourceSyncEngine;
import io.memoryos.connector.sync.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.application.DefaultObjectWriteService;
import io.memoryos.objectstorage.application.ObjectUploadProperties;
import io.memoryos.objectstorage.persistence.JdbcObjectWriteRepository;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresLarkSyncTest {
    private static final String SPACE = "7000000000000000001";
    private static final String FOLDER = "fldRootFolder";
    private static final Instant EDITED = Instant.parse("2026-10-11T17:19:58Z");
    private static final byte[] SNAPSHOT = "{\"schema\":\"memoryos-lark-docx-v1\"}".getBytes(StandardCharsets.UTF_8);

    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private TenantId tenant;
    private ActorId owner;
    private SourceId source;
    private CredentialId credential;
    private LarkGateway.Session session;
    private JdbcSourceSyncRepository attempts;
    private JdbcLarkSourceRepository lark;
    private SourceSyncEngine service;
    private LarkConnectionService currentConnections;

    @AfterEach
    void closeDatabase() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
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
        var runs = new JdbcLarkSyncRepository(jdbc);
        lark = new JdbcLarkSourceRepository(jdbc, sources);
        var credentialRows = new JdbcLarkCredentialRepository(jdbc, sources,
                new LarkCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "test"));
        currentConnections = new LarkConnectionService(credentialRows, lark, mock(LarkGateway.class), manager);
        var indexing = new JdbcIndexAttemptRepository(jdbc, sources, documents, SourceSyncAdapters.registry(
                SourceSyncAdapters.credentials(SourceType.LARK, currentConnections::current)));

        session = mock(LarkGateway.Session.class);
        var connections = mock(LarkConnectionService.class);
        when(connections.state(any(), any())).thenReturn(new LarkConnectionService.State(credential, "Lark app",
                "ACTIVE", 1L));
        when(connections.current(any(), any(), anyLong())).thenReturn(true);
        when(connections.open(any(), any())).thenAnswer(_ ->
                new LarkConnectionService.Connection(session, 1L, LarkGateway.Region.LARK));

        var storage = mock(ObjectStorage.class);
        var storedMetadata = new ConcurrentHashMap<ObjectKey, ObjectMetadata>();
        doAnswer(call -> {
            byte[] value = call.getArgument(1);
            storedMetadata.put(call.getArgument(0), new ObjectMetadata(value.length, call.getArgument(2), checksum(value)));
            return null;
        }).when(storage).write(any(), any(), any());
        when(storage.inspect(any())).thenAnswer(call -> storedMetadata.get(call.getArgument(0)));
        var writes = new DefaultObjectWriteService(new JdbcStoredObjectRepository(jdbc),
                new JdbcObjectWriteRepository(jdbc), storage,
                new ObjectUploadProperties(Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(5),
                        Duration.ofMinutes(1), 16), manager);
        service = new SourceSyncEngine(attempts, sources, items, indexing, documents, writes,
                SourceSyncAdapters.registry(new LarkSyncAdapter(runs, lark, connections)), manager);
        seedSource(List.of(new RootView(RootKind.WIKI_SPACE, SPACE, "Wiki"),
                new RootView(RootKind.DRIVE_FOLDER, FOLDER, "Drive")));
        // Unless a test says otherwise, every root is empty.
        when(session.nodes(eq(SPACE), any(), any())).thenReturn(new LarkGateway.NodePage(List.of(), null));
        when(session.files(any(), any())).thenReturn(new LarkGateway.FilePage(List.of(), null));
    }

    @Test
    void aRunReadsADocumentASheetAndAFileThroughTheTreeAndQueuesThemForIndexing() {
        // A Wiki page is a document and, having children, the container of the sheet below it.
        when(session.nodes(SPACE, null, null)).thenReturn(new LarkGateway.NodePage(List.of(
                node("node-page", "docx-page", ObjectType.DOCX, "Sổ tay", true)), null));
        when(session.nodes(SPACE, "node-page", null)).thenReturn(new LarkGateway.NodePage(List.of(
                node("node-sheet", "sheet-prices", ObjectType.SHEET, "Bảng giá", false)), null));
        // A Drive folder holds a file and a folder, whose document is found one level down.
        when(session.files(FOLDER, null)).thenReturn(new LarkGateway.FilePage(List.of(
                file("file-report", "bao-cao.pdf", ObjectType.FILE), file("fldInner", "Nội bộ", ObjectType.FOLDER)), null));
        when(session.files("fldInner", null)).thenReturn(new LarkGateway.FilePage(List.of(
                file("docx-inner", "Nội quy", ObjectType.DOCX)), null));
        when(session.docx(any())).thenReturn(new LarkGateway.Snapshot("memoryos-lark-docx-v1", SNAPSHOT));
        when(session.sheet("sheet-prices")).thenReturn(new LarkGateway.Snapshot("memoryos-lark-sheet-v1", SNAPSHOT));
        when(session.download(eq("file-report"), anyInt())).thenReturn(new LarkGateway.Content("file-report",
                "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8)));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));

        for (String item : List.of("docx-page", "sheet-prices", "file-report", "docx-inner"))
            assertEquals("PENDING", status(item), item + " is held and waiting to be indexed");
        assertEquals(4, counter("acquired"));
        assertEquals(List.of("LARK_DOCX", "LARK_DOCX", "BINARY", "LARK_SHEET"), jdbc.sql("""
                SELECT v.input_format FROM connector_item_versions v
                JOIN connector_items i ON i.tenant_id = v.tenant_id AND i.current_version_id = v.id
                WHERE v.tenant_id = :tenant ORDER BY i.provider_file_id
                """).param("tenant", tenant.value()).query(String.class).list());
        // The version is the second Lark last edited the object; the link is the address Lark returned.
        assertEquals(Long.toString(EDITED.getEpochSecond()), version("docx-page", "provider_version"));
        assertEquals("https://tenant.larksuite.com/wiki/node-page", version("docx-page", "source_url"));
        // Lark named the download by its token, so the listing's name is kept.
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM stored_objects WHERE tenant_id = :tenant AND filename = 'bao-cao.pdf'")
                .param("tenant", tenant.value()).query(Integer.class).single());
        assertEquals(List.of("SUCCEEDED"), runStatuses());

        // A second run over unchanged objects neither reads them again nor queues another attempt.
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));
        assertEquals(4, counter("acquired"));
        verify(session, times(2)).docx(any());
        verify(session, times(1)).download(any(), anyInt());
    }

    @Test
    void anObjectEditedSinceIsReadAgain() {
        when(session.files(FOLDER, null)).thenReturn(new LarkGateway.FilePage(List.of(
                file("docx-policy", "Chính sách", ObjectType.DOCX)), null));
        when(session.docx("docx-policy")).thenReturn(new LarkGateway.Snapshot("memoryos-lark-docx-v1", SNAPSHOT));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));

        Instant later = EDITED.plusSeconds(60);
        when(session.files(FOLDER, null)).thenReturn(new LarkGateway.FilePage(List.of(new LarkGateway.File(
                "docx-policy", "Chính sách", ObjectType.DOCX, FOLDER, "https://tenant.larksuite.com/docx/docx-policy",
                EDITED, later, null)), null));
        when(session.docx("docx-policy")).thenReturn(new LarkGateway.Snapshot("memoryos-lark-docx-v1",
                "{\"schema\":\"memoryos-lark-docx-v1\",\"edited\":true}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));

        assertEquals(2, counter("acquired"));
        assertEquals(Long.toString(later.getEpochSecond()), version("docx-policy", "provider_version"));
    }

    @Test
    void anObjectOfAKindThatIsNotReadIsSkippedAndNeverHeld() {
        when(session.nodes(SPACE, null, null)).thenReturn(new LarkGateway.NodePage(List.of(
                node("node-base", "bascn-base", ObjectType.BITABLE, "Bảng dữ liệu", false),
                node("node-old", "doccn-old", ObjectType.DOC, "Tài liệu cũ", false),
                node("node-other", "x-unknown", ObjectType.OTHER, "Khác", false)), null));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));

        assertEquals("ABSENT", status("bascn-base"));
        assertEquals(3, counter("skipped"));
        assertEquals(0, counter("acquired"));
        verify(session, never()).docx(any());
        verify(session, never()).download(any(), anyInt());
    }

    @Test
    void aCompleteListingRemovesWhatItDidNotSee() {
        seedItem("docx-kept", "Còn");
        seedItem("docx-gone", "Đã xoá");
        when(session.files(FOLDER, null)).thenReturn(new LarkGateway.FilePage(List.of(
                file("docx-kept", "Còn", ObjectType.DOCX)), null));
        when(session.docx("docx-kept")).thenReturn(new LarkGateway.Snapshot("memoryos-lark-docx-v1", SNAPSHOT));

        var work = claim(enqueue());
        assertEquals(ConnectorSyncPort.Result.CONTINUED, service.execute(work), "removals are handed back in batches");
        assertEquals("DELETING", status("docx-gone"));
        assertNotEquals("DELETING", status("docx-kept"));
        assertEquals(1, counter("removed"));

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(work.operationId())));
        assertEquals(List.of("SUCCEEDED"), runStatuses());
    }

    @Test
    void aListingThatCannotFinishRemovesNothingAndFailsTheRunWithItsCode() {
        seedItem("docx-held", "Đang giữ");
        // The app was removed from the space: the second root is never reached and nothing is pruned.
        when(session.nodes(SPACE, null, null)).thenThrow(
                new LarkProviderException(LarkProviderException.Failure.AUTHORIZATION));

        assertEquals(ConnectorSyncPort.Result.FAILED, service.execute(claim(enqueue())));

        assertNotEquals("DELETING", status("docx-held"),
                "a space that cannot be listed must never cause documents to be deleted");
        assertEquals(List.of("FAILED"), runStatuses());
        verify(session, never()).files(any(), any());
        var summary = new JdbcSourceQueryRepository(jdbc).summary(tenant, owner, source, true, true, true);
        assertEquals(SourceStatus.FAILED, summary.status());
        assertEquals("SOURCE_LARK_AUTHORIZATION", summary.errorCode());
    }

    @Test
    void anObjectThatCannotBeReadIsAnItemErrorAndTheRunStillCompletes() {
        when(session.files(FOLDER, null)).thenReturn(new LarkGateway.FilePage(List.of(
                file("docx-refused", "Bị từ chối", ObjectType.DOCX), file("docx-huge", "Quá lớn", ObjectType.DOCX),
                file("docx-vanished", "Vừa xoá", ObjectType.DOCX), file("docx-fine", "Đọc được", ObjectType.DOCX)), null));
        doThrow(new LarkProviderException(LarkProviderException.Failure.AUTHORIZATION)).when(session).docx("docx-refused");
        doThrow(new LarkProviderException(LarkProviderException.Failure.LIMIT_EXCEEDED)).when(session).docx("docx-huge");
        doThrow(new LarkProviderException(LarkProviderException.Failure.NOT_FOUND)).when(session).docx("docx-vanished");
        doReturn(new LarkGateway.Snapshot("memoryos-lark-docx-v1", SNAPSHOT)).when(session).docx("docx-fine");

        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(enqueue())));

        assertEquals("PENDING", status("docx-fine"));
        assertEquals("ABSENT", status("docx-refused"));
        assertEquals(List.of("SOURCE_LARK_AUTHORIZATION", "SOURCE_LARK_LIMIT_EXCEEDED"), jdbc.sql("""
                SELECT code FROM source_run_errors WHERE tenant_id = :tenant ORDER BY code
                """).param("tenant", tenant.value()).query(String.class).list());
        assertEquals("PROVIDER", jdbc.sql("""
                SELECT DISTINCT stage FROM source_run_errors WHERE tenant_id = :tenant
                """).param("tenant", tenant.value()).query(String.class).single());
    }

    @Test
    void aLongListingIsHandedBackAndResumedWhereItStopped() {
        // Forty pages of one document each: more than one slice lists.
        when(session.files(eq(FOLDER), any())).thenAnswer(call -> {
            String page = call.getArgument(1);
            int index = page == null ? 0 : Integer.parseInt(page);
            return new LarkGateway.FilePage(List.of(file("docx-" + index, "Trang " + index, ObjectType.DOCX)),
                    index == 39 ? null : Integer.toString(index + 1));
        });
        when(session.docx(any())).thenReturn(new LarkGateway.Snapshot("memoryos-lark-docx-v1", SNAPSHOT));

        var work = claim(enqueue());
        var result = service.execute(work);
        int slices = 1;
        while (result == ConnectorSyncPort.Result.CONTINUED && slices < 20) {
            result = service.execute(claim(work.operationId()));
            slices++;
        }

        assertEquals(ConnectorSyncPort.Result.COMPLETED, result);
        assertTrue(slices > 1, "the listing took more than one slice");
        assertEquals(40, counter("acquired"));
        // Each page was listed once: a resumed slice continues from its checkpoint, not from the start.
        verify(session, times(1)).files(eq(FOLDER), isNull());
        verify(session, times(1)).files(FOLDER, "20");
        verify(session, times(40)).docx(any());
        // The space was walked before the folder and is not walked again by a later slice.
        verify(session, times(1)).nodes(eq(SPACE), isNull(), isNull());
    }

    @Test
    void aRefusedCredentialFailsTheRunAndClosesIt() {
        when(session.nodes(SPACE, null, null)).thenThrow(
                new LarkProviderException(LarkProviderException.Failure.AUTHENTICATION));

        assertEquals(ConnectorSyncPort.Result.FAILED, service.execute(claim(enqueue())));

        assertEquals(List.of("FAILED"), runStatuses());
        var summary = new JdbcSourceQueryRepository(jdbc).summary(tenant, owner, source, true, true, true);
        assertEquals("SOURCE_LARK_AUTHENTICATION", summary.errorCode());
    }

    @Test
    void theStateOfASourceNamesACredentialThatNeedsAnUpdate() {
        jdbc.sql("UPDATE credentials SET status = 'NEEDS_UPDATE' WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.value()).param("id", credential.value()).update();

        // The Source stays readable, pausable and schedulable; only opening a session needs a usable credential.
        assertEquals("NEEDS_UPDATE", currentConnections.state(tenant, source).status());
        assertThrows(LarkException.class, () -> currentConnections.openCredential(tenant, credential));
    }

    @Test
    void onlySourcesWithRootsAndNoRunInFlightAreDue() {
        var runs = new JdbcLarkSyncRepository(jdbc);
        assertEquals(List.of(source), runs.due(10).stream().map(due -> due.sourceId()).toList());
        var live = enqueue();
        assertTrue(runs.due(10).isEmpty(), "a Source with an attempt in flight is not due again");
        assertEquals(ConnectorSyncPort.Result.COMPLETED, service.execute(claim(live)));
        assertTrue(runs.due(10).isEmpty(), "a completed run moves the next one an interval ahead");
    }

    private LarkGateway.Node node(String nodeToken, String objToken, ObjectType type, String title, boolean hasChild) {
        return new LarkGateway.Node(nodeToken, SPACE, null, type, objToken, title, hasChild, EDITED, EDITED,
                "https://tenant.larksuite.com/wiki/" + nodeToken);
    }

    private static LarkGateway.File file(String token, String name, ObjectType type) {
        return new LarkGateway.File(token, name, type, FOLDER, "https://tenant.larksuite.com/" + type.wire() + "/" + token,
                EDITED, EDITED, null);
    }

    private SourceOperationId enqueue() {
        return Objects.requireNonNull(tx.execute(_ ->
                attempts.enqueue(tenant, source, 1L, SourceRunTrigger.MANUAL, owner).id()));
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

    private void seedTenant() {
        jdbc.sql("INSERT INTO actors (id) VALUES (:id)").param("id", owner.value()).update();
        jdbc.sql("""
                INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference)
                VALUES (:id, 'lark-sync', 'Lark sync', 'ACTIVE', 'TEST')
                """).param("id", tenant.value()).update();
        jdbc.sql("""
                INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                VALUES (:tenant, :actor, 'OWNER', 'ACTIVE')
                """).param("tenant", tenant.value()).param("actor", owner.value()).update();
        jdbc.sql("""
                INSERT INTO credentials (id, tenant_id, name, credential_kind, status, owner_actor_id)
                VALUES (:id, :tenant, 'Lark app', 'LARK_APP', 'ACTIVE', :actor)
                """).param("id", credential.value()).param("tenant", tenant.value())
                .param("actor", owner.value()).update();
        jdbc.sql("""
                INSERT INTO lark_credentials (tenant_id, credential_id, region, app_id, secret_ciphertext,
                    secret_nonce, secret_key_version)
                VALUES (:tenant, :credential, 'LARK', 'cli_a1b2c3d4e5f6a7b8', :ciphertext, :nonce, 'v1')
                """).param("tenant", tenant.value()).param("credential", credential.value())
                .param("ciphertext", new byte[32]).param("nonce", new byte[12]).update();
    }

    private void seedSource(List<RootView> roots) {
        tx.executeWithoutResult(_ -> lark.create(tenant, source, owner, null, "Lark", credential,
                SourceAccess.PUBLIC, 30, roots));
    }

    /** Seeds an item the Source already holds, as a completed acquisition of an earlier run would have left it. */
    private void seedItem(String providerFileId, String name) {
        UUID connector = jdbc.sql("""
                SELECT connector_id FROM connector_credential_pairs WHERE tenant_id = :tenant AND id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO connector_items (id, tenant_id, connector_id, provider_file_id, content_sha256, status)
                VALUES (:id, :tenant, :connector, :file, :sha, 'PENDING')
                """).param("id", UUID.randomUUID()).param("tenant", tenant.value()).param("connector", connector)
                .param("file", providerFileId).param("sha", "0".repeat(64)).update();
        jdbc.sql("""
                INSERT INTO lark_items (tenant_id, source_id, provider_file_id, object_type, name)
                VALUES (:tenant, :source, :file, 'DOCX', :name)
                """).param("tenant", tenant.value()).param("source", source.value()).param("file", providerFileId)
                .param("name", name).update();
    }

    private String status(String providerFileId) {
        return jdbc.sql("""
                SELECT i.status FROM connector_items i
                JOIN connector_credential_pairs p ON p.tenant_id = i.tenant_id AND p.connector_id = i.connector_id
                WHERE p.tenant_id = :tenant AND p.id = :source AND i.provider_file_id = :file
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("file", providerFileId).query(String.class).optional().orElse("ABSENT");
    }

    private @Nullable String version(String providerFileId, String column) {
        return jdbc.sql("SELECT v." + column + """
                 FROM connector_item_versions v
                JOIN connector_items i ON i.tenant_id = v.tenant_id AND i.current_version_id = v.id
                WHERE v.tenant_id = :tenant AND i.provider_file_id = :file
                """).param("tenant", tenant.value()).param("file", providerFileId).query(String.class).single();
    }

    private long counter(String column) {
        return jdbc.sql("SELECT COALESCE(SUM(" + column + "), 0) FROM source_sync_attempts WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Long.class).single();
    }

    private List<String> runStatuses() {
        return jdbc.sql("SELECT status FROM lark_sync_runs WHERE tenant_id = :tenant ORDER BY created_at")
                .param("tenant", tenant.value()).query(String.class).list();
    }

    private static ContentSha256 checksum(byte[] value) {
        try {
            return new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
