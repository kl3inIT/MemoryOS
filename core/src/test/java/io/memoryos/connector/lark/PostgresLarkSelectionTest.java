package io.memoryos.connector.lark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkException;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkProviderException;
import io.memoryos.connector.LarkSourceService;
import io.memoryos.connector.LarkSourceService.Root;
import io.memoryos.connector.LarkSourceService.RootKind;
import io.memoryos.connector.LarkSourceService.RootView;
import io.memoryos.connector.LarkSourceService.Scope;
import io.memoryos.connector.LarkSourceService.SpaceOption;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceSelectionProcessor;
import io.memoryos.connector.lark.persistence.JdbcLarkCredentialRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkSelectionRepository;
import io.memoryos.connector.lark.persistence.JdbcLarkSourceRepository;
import io.memoryos.connector.lark.persistence.LarkCredentialConfiguration;
import io.memoryos.connector.source.SourceAccessPolicy;
import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.source.persistence.JdbcSourceGroupRepository;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.connector.sync.SourceSyncAdapters;
import io.memoryos.connector.sync.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository;
import io.memoryos.iam.group.DefaultGroupScopeService;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.persistence.GroupInvariantRepository;
import io.memoryos.iam.group.persistence.GroupProjectionRepository;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresLarkSelectionTest {
    private static final String SPACE = "7695460898437074458";
    private static final String FOLDER_LINK = "https://tenant.jp.larksuite.com/drive/folder/fldDriveTest01";

    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private TenantId tenant;
    private ActorId owner;
    private CredentialId credential;
    private LarkGateway.Session session;
    private LarkConnectionService connections;
    private LarkSourceService sources;
    private LarkSelectionAdapter processor;
    private JdbcLarkSelectionRepository selections;

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
        credential = new CredentialId(UUID.randomUUID());
        seed();

        var authorization = new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), new IamLockRepository(jdbc));
        var sourceRows = new JdbcSourceRepository(jdbc, event -> { });
        var documents = new JdbcSourceDocumentRepository(jdbc);
        var credentials = new JdbcLarkCredentialRepository(jdbc, sourceRows,
                new LarkCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "v1"));
        selections = new JdbcLarkSelectionRepository(jdbc);
        var lark = new JdbcLarkSourceRepository(jdbc, sourceRows);
        var access = new SourceAccessPolicy(authorization, sourceRows,
                new DefaultGroupScopeService(new GroupInvariantRepository(jdbc), new GroupProjectionRepository(jdbc)),
                TestDatabase.noAudit(), SourceSyncAdapters.registry());

        session = mock(LarkGateway.Session.class);
        when(session.space(SPACE)).thenReturn(new LarkGateway.Space(SPACE, "MemoryOS Test", "private"));
        when(session.folder("fldDriveTest01")).thenReturn(new LarkGateway.Folder("fldDriveTest01", "MemoryOS Drive Test"));
        connections = mock(LarkConnectionService.class);
        when(connections.state(any(), any())).thenReturn(new LarkConnectionService.State(credential, "Lark app",
                "ACTIVE", 1L));
        when(connections.openCredential(any(), any())).thenAnswer(_ ->
                new LarkConnectionService.Connection(session, 1L, LarkGateway.Region.LARK));

        var service = new DefaultLarkSourceService(access, authorization, connections, lark, selections, credentials,
                new JdbcSourceGroupRepository(jdbc, event -> { }), sourceRows, new JdbcSourceSyncRepository(jdbc),
                new JdbcIndexAttemptRepository(jdbc, sourceRows, documents, SourceSyncAdapters.registry()),
                documents, manager, TestDatabase.noAudit());
        sources = TestDatabase.transactionalProxy(service, LarkSourceService.class, manager);
        processor = new LarkSelectionAdapter(selections, service, connections, manager);
    }

    @Test
    void aVerifiedScopeCreatesTheSourceWithItsRootsAndStartsItsFirstRun() {
        var receipt = sources.create(owner, UUID.randomUUID(), "Lark", credential, scope(), SourceAccess.PUBLIC, List.of());

        assertEquals("VALIDATE_LARK_SELECTION", receipt.operation().type().name());
        assertEquals(SourceSelectionProcessor.Result.COMPLETED, processor.execute(claim(receipt.operation().id())));

        var configuration = sources.configuration(owner, receipt.sourceId());
        assertEquals(2, configuration.rootCount());
        assertEquals(30, configuration.syncIntervalMinutes());
        assertEquals(credential, configuration.credentialId());
        assertNull(configuration.pendingSelectionOperation());
        // A folder is stored by its token, whatever link named it, with the name Lark gave it.
        assertEquals(List.of(new RootView(RootKind.WIKI_SPACE, SPACE, "MemoryOS Test"),
                new RootView(RootKind.DRIVE_FOLDER, "fldDriveTest01", "MemoryOS Drive Test")),
                sources.roots(owner, receipt.sourceId()));
        assertEquals("LARK", jdbc.sql("""
                SELECT c.connector_type FROM connectors c
                JOIN connector_credential_pairs p ON p.tenant_id = c.tenant_id AND p.connector_id = c.id
                WHERE p.id = :id
                """).param("id", receipt.sourceId().value()).query(String.class).single());
        assertEquals(1, attempts(receipt.sourceId()), "a new Source starts synchronizing");
        assertEquals("SUCCEEDED", operationStatus(receipt.operation().id()));
    }

    @Test
    void aRootTheAppCannotReadFailsTheRequestWithoutCreatingASource() {
        when(session.folder("fldDriveTest01")).thenThrow(
                new LarkProviderException(LarkProviderException.Failure.AUTHORIZATION));
        var receipt = sources.create(owner, UUID.randomUUID(), "Lark", credential, scope(), SourceAccess.PUBLIC, List.of());

        assertEquals(SourceSelectionProcessor.Result.FAILED, processor.execute(claim(receipt.operation().id())));

        assertEquals("SOURCE_LARK_AUTHORIZATION", operationError(receipt.operation().id()));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM lark_sources WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Integer.class).single(), "nothing is stored");
        assertThrows(SourceException.class, () -> sources.configuration(owner, receipt.sourceId()));
    }

    @Test
    void aSilentLarkLeavesTheRequestToBeVerifiedAgainAndARefusedCredentialIsMarked() {
        when(session.space(SPACE)).thenThrow(new LarkProviderException(LarkProviderException.Failure.UNAVAILABLE));
        var receipt = sources.create(owner, UUID.randomUUID(), "Lark", credential, scope(), SourceAccess.PUBLIC, List.of());
        assertEquals(SourceSelectionProcessor.Result.CONTINUED, processor.execute(claim(receipt.operation().id())));
        verify(connections, never()).authenticationFailed(any(), any(), any(Long.class));

        when(connections.openCredential(any(), any())).thenThrow(
                new LarkProviderException(LarkProviderException.Failure.AUTHENTICATION));
        var refused = sources.create(owner, UUID.randomUUID(), "Lark 2", credential, scope(), SourceAccess.PUBLIC, List.of());
        assertEquals(SourceSelectionProcessor.Result.FAILED, processor.execute(claim(refused.operation().id())));
        assertEquals("SOURCE_LARK_AUTHENTICATION", operationError(refused.operation().id()));
        verify(connections).authenticationFailed(tenant, credential, 1L);
    }

    @Test
    void aRequestIsCheckedBeforeItIsAccepted() {
        for (var invalid : List.of(
                new Scope(List.of(), 30),
                new Scope(List.of(new Root(RootKind.WIKI_SPACE, "not-a-space")), 30),
                new Scope(List.of(new Root(RootKind.DRIVE_FOLDER, "https://evil.example/drive/folder/fldDriveTest01")), 30),
                new Scope(List.of(new Root(RootKind.DRIVE_FOLDER, "https://tenant.feishu.cn/drive/folder/fldDriveTest01")), 30),
                new Scope(List.of(new Root(RootKind.DRIVE_FOLDER, "https://tenant.larksuite.com/docx/fldDriveTest01")), 30),
                // The same folder by its link and by its token is one root named twice.
                new Scope(List.of(new Root(RootKind.DRIVE_FOLDER, FOLDER_LINK),
                        new Root(RootKind.DRIVE_FOLDER, "fldDriveTest01")), 30),
                new Scope(List.of(new Root(RootKind.WIKI_SPACE, SPACE)), 0))) {
            assertThrows(SourceException.class, () -> sources.create(owner, UUID.randomUUID(), "Lark", credential,
                    invalid, SourceAccess.PUBLIC, List.of()), invalid.toString());
        }
        assertThrows(SourceException.class, () -> sources.create(owner, UUID.randomUUID(), " ", credential, scope(),
                SourceAccess.PUBLIC, List.of()));
        // Lark's permissions are not read yet, so a Source cannot admit readers by them.
        assertThrows(SourceException.class, () -> sources.create(owner, UUID.randomUUID(), "Lark", credential, scope(),
                SourceAccess.SYNC, List.of()));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM source_selection_operations WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Integer.class).single());
    }

    @Test
    void repeatingARequestRecoversItsReceiptInsteadOfStartingAgain() {
        UUID requestId = UUID.randomUUID();
        var first = sources.create(owner, requestId, "Lark", credential, scope(), SourceAccess.PUBLIC, List.of());
        var again = sources.create(owner, requestId, "Lark", credential, scope(), SourceAccess.PUBLIC, List.of());

        assertEquals(first.operation().id(), again.operation().id());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM source_selection_operations WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Integer.class).single());
        assertEquals(first, sources.selectionRequest(owner, requestId));
        // The same request ID with other content is another request, and is refused.
        assertThrows(SourceException.class, () -> sources.create(owner, requestId, "Other name", credential, scope(),
                SourceAccess.PUBLIC, List.of()));
    }

    @Test
    void replacingTheScopeNeedsTheCurrentRevisionsAndAppliesOnceVerified() {
        var created = sources.create(owner, UUID.randomUUID(), "Lark", credential, scope(), SourceAccess.PRIVATE, List.of());
        processor.execute(claim(created.operation().id()));
        var configuration = sources.configuration(owner, created.sourceId());
        var onlySpace = new Scope(List.of(new Root(RootKind.WIKI_SPACE, SPACE)), 60);

        assertThrows(SourceException.class, () -> sources.replaceScope(owner, UUID.randomUUID(), created.sourceId(),
                configuration.scopeRevision() + 1, configuration.credentialRevision(), onlySpace));
        assertThrows(SourceException.class, () -> sources.replaceScope(owner, UUID.randomUUID(), created.sourceId(),
                configuration.scopeRevision(), configuration.credentialRevision() + 1, onlySpace));

        var receipt = sources.replaceScope(owner, UUID.randomUUID(), created.sourceId(), configuration.scopeRevision(),
                configuration.credentialRevision(), onlySpace);
        assertEquals(created.sourceId(), receipt.sourceId());
        assertNotNull(sources.configuration(owner, created.sourceId()).pendingSelectionOperation());
        // The old roots stay in force until the new ones are verified.
        assertEquals(2, sources.roots(owner, created.sourceId()).size());

        assertEquals(SourceSelectionProcessor.Result.COMPLETED, processor.execute(claim(receipt.operation().id())));
        var replaced = sources.configuration(owner, created.sourceId());
        assertEquals(configuration.scopeRevision() + 1, replaced.scopeRevision());
        assertEquals(60, replaced.syncIntervalMinutes());
        assertEquals(List.of(new RootView(RootKind.WIKI_SPACE, SPACE, "MemoryOS Test")),
                sources.roots(owner, created.sourceId()));
    }

    @Test
    void theScheduleAndThePauseAreFencedByTheirRevision() {
        var created = sources.create(owner, UUID.randomUUID(), "Lark", credential, scope(), SourceAccess.PUBLIC, List.of());
        processor.execute(claim(created.operation().id()));
        var configuration = sources.configuration(owner, created.sourceId());

        assertThrows(SourceException.class, () -> sources.updateSchedule(owner, created.sourceId(),
                configuration.scheduleRevision() + 1, 120));
        assertThrows(SourceException.class, () -> sources.updateSchedule(owner, created.sourceId(),
                configuration.scheduleRevision(), 0));
        var rescheduled = sources.updateSchedule(owner, created.sourceId(), configuration.scheduleRevision(), 120);
        assertEquals(120, rescheduled.syncIntervalMinutes());

        var paused = sources.setPaused(owner, created.sourceId(), rescheduled.scheduleRevision(), true);
        assertEquals(true, paused.syncPaused());
        assertEquals(false, sources.setPaused(owner, created.sourceId(), paused.scheduleRevision(), false).syncPaused());
    }

    @Test
    void theSpacesOfferedAreThoseTheAppIsAMemberOfNow() {
        when(session.spaces(null)).thenReturn(new LarkGateway.SpacePage(
                List.of(new LarkGateway.Space(SPACE, "MemoryOS Test", "private")), "next"));
        when(session.spaces("next")).thenReturn(new LarkGateway.SpacePage(
                List.of(new LarkGateway.Space("7000000000000000002", "Kỹ thuật", "public")), null));

        assertEquals(List.of(new SpaceOption(SPACE, "MemoryOS Test"), new SpaceOption("7000000000000000002", "Kỹ thuật")),
                sources.spaces(owner, credential));

        when(session.spaces(null)).thenThrow(new LarkProviderException(LarkProviderException.Failure.AUTHORIZATION));
        assertEquals("SOURCE_LARK_SCOPE_MISSING",
                assertThrows(LarkException.class, () -> sources.spaces(owner, credential)).code());
        assertThrows(SourceException.class, () -> sources.spaces(owner, new CredentialId(UUID.randomUUID())));
    }

    private static Scope scope() {
        return new Scope(List.of(new Root(RootKind.WIKI_SPACE, SPACE), new Root(RootKind.DRIVE_FOLDER, FOLDER_LINK)), 30);
    }

    /** Stands in for the relay, which stamps the delivery a worker then claims. */
    private SourceSelectionProcessor.Work claim(SourceOperationId operation) {
        UUID delivery = UUID.randomUUID();
        jdbc.sql("""
                UPDATE source_selection_operations SET delivery_id = :delivery, dispatched_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND id = :id
                """).param("delivery", delivery).param("tenant", tenant.value())
                .param("id", operation.value()).update();
        return Objects.requireNonNull(tx.execute(_ ->
                selections.operations().claim(tenant, operation, delivery).orElseThrow()));
    }

    private int attempts(SourceId source) {
        return jdbc.sql("SELECT COUNT(*) FROM source_sync_attempts WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).query(Integer.class).single();
    }

    private String operationStatus(SourceOperationId operation) {
        return jdbc.sql("SELECT status FROM source_selection_operations WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.value()).param("id", operation.value()).query(String.class).single();
    }

    private String operationError(SourceOperationId operation) {
        return jdbc.sql("SELECT error_code FROM source_selection_operations WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.value()).param("id", operation.value()).query(String.class).single();
    }

    private void seed() {
        jdbc.sql("INSERT INTO actors (id) VALUES (:id)").param("id", owner.value()).update();
        jdbc.sql("""
                INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference)
                VALUES (:id, 'lark-scope', 'Lark scope', 'ACTIVE', 'TEST')
                """).param("id", tenant.value()).update();
        jdbc.sql("""
                INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                VALUES (:tenant, :actor, 'OWNER', 'ACTIVE')
                """).param("tenant", tenant.value()).param("actor", owner.value()).update();
        jdbc.sql("INSERT INTO iam_groups(tenant_id, id, name, system_key) VALUES (:tenant, :tenant, 'Admin', 'ADMIN')")
                .param("tenant", tenant.value()).update();
        jdbc.sql("""
                INSERT INTO iam_group_capability_grants(tenant_id, group_id, capability)
                VALUES (:tenant, :tenant, 'SYSTEM_ADMIN')
                """).param("tenant", tenant.value()).update();
        jdbc.sql("INSERT INTO iam_group_memberships(tenant_id, group_id, actor_id) VALUES (:tenant, :tenant, :actor)")
                .param("tenant", tenant.value()).param("actor", owner.value()).update();
        jdbc.sql("""
                INSERT INTO credentials (id, tenant_id, name, credential_kind, status, owner_actor_id)
                VALUES (:id, :tenant, 'Lark app', 'LARK_APP', 'ACTIVE', :actor)
                """).param("id", credential.value()).param("tenant", tenant.value()).param("actor", owner.value()).update();
        jdbc.sql("""
                INSERT INTO lark_credentials (tenant_id, credential_id, region, app_id, secret_ciphertext,
                    secret_nonce, secret_key_version)
                VALUES (:tenant, :credential, 'LARK', 'cli_a1b2c3d4e5f6a7b8', :ciphertext, :nonce, 'v1')
                """).param("tenant", tenant.value()).param("credential", credential.value())
                .param("ciphertext", new byte[32]).param("nonce", new byte[12]).update();
    }
}
