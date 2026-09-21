package io.memoryos.connector.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointConnectionService;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointSelectionProcessor;
import io.memoryos.connector.SharePointSourceService;
import io.memoryos.connector.SharePointSourceService.Scope;
import io.memoryos.connector.SharePointSourceService.ScopeMode;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.persistence.JdbcSharePointCredentialRepository;
import io.memoryos.connector.persistence.JdbcSharePointSelectionRepository;
import io.memoryos.connector.persistence.JdbcSharePointSourceRepository;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceGroupRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.connector.persistence.JdbcSourceSyncRepository;
import io.memoryos.connector.persistence.SharePointCredentialConfiguration;
import io.memoryos.iam.group.DefaultGroupScopeService;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.persistence.GroupInvariantRepository;
import io.memoryos.iam.group.persistence.GroupProjectionRepository;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
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

/**
 * Accepting a SharePoint scope and applying it once Microsoft has resolved every address. The library is
 * matched by the path of its URL, which is what lets a site in any language resolve.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresSharePointSelectionTest {
    private static final String LIBRARY_URL = "https://contoso.sharepoint.com/sites/Finance/Shared%20Documents";

    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private TenantId tenant;
    private ActorId owner;
    private CredentialId credential;
    private SharePointProvider.Session session;
    private SharePointSourceService sources;
    private SharePointSelectionProcessor processor;
    private JdbcSharePointSelectionRepository selections;
    private JdbcSharePointSourceRepository sharePoint;

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
        var credentials = new JdbcSharePointCredentialRepository(jdbc, sourceRows,
                new SharePointCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "v1"));
        selections = new JdbcSharePointSelectionRepository(jdbc);
        sharePoint = new JdbcSharePointSourceRepository(jdbc, sourceRows);
        var access = new SourceAccessPolicy(authorization, sourceRows,
                new DefaultGroupScopeService(new GroupInvariantRepository(jdbc), new GroupProjectionRepository(jdbc)), io.memoryos.TestDatabase.noAudit());

        session = mock(SharePointProvider.Session.class);
        when(session.root()).thenReturn(new SharePointProvider.RootSite("site-1", "https://contoso.sharepoint.com",
                "contoso.sharepoint.com"));
        when(session.site(any(), any())).thenReturn(new SharePointProvider.Site("site-1",
                "https://contoso.sharepoint.com/sites/Finance", "Finance", false));
        var connections = mock(SharePointConnectionService.class);
        when(connections.state(any(), any())).thenReturn(new SharePointConnectionService.State(credential,
                "Entra app", "ACTIVE", 1L, "contoso.sharepoint.com"));
        when(connections.openCredential(any(), any())).thenAnswer(_ ->
                new SharePointConnectionService.Connection(session, 1L, "contoso.sharepoint.com"));

        var service = new DefaultSharePointSourceService(access, authorization, connections, sharePoint, selections,
                credentials, new JdbcSourceGroupRepository(jdbc, event -> { }), sourceRows,
                new JdbcSourceSyncRepository(jdbc),
                new io.memoryos.connector.persistence.JdbcSharePointSyncRepository(jdbc, new JdbcSourceSyncRepository(jdbc)),
                new JdbcIndexAttemptRepository(jdbc, sourceRows, documents,
                        mock(io.memoryos.connector.ProviderAuthorityService.class)),
                documents, new SharePointSelectionPolicy(1000, 3_145_728), manager, io.memoryos.TestDatabase.noAudit());
        sources = TestDatabase.transactionalProxy(service, SharePointSourceService.class, manager);
        processor = new DefaultSharePointSelectionProcessor(selections, service, connections, manager);
    }

    @Test
    void resolvedScopeCreatesTheSourceAndStartsItsFirstRun() {
        // The library is named in Vietnamese but its URL path is still the English one.
        when(session.libraries("site-1")).thenReturn(List.of(new SharePointProvider.Library("drive-1", "Tài liệu",
                "/sites/Finance/Shared Documents")));
        var receipt = sources.create(owner, UUID.randomUUID(), "Finance", credential, scope(LIBRARY_URL),
                SourceAccess.PUBLIC, List.of());

        var result = processor.execute(claim(receipt.operation().id()));

        assertEquals(SharePointSelectionProcessor.Result.COMPLETED, result);
        var configuration = sources.configuration(owner, receipt.sourceId());
        assertEquals(ScopeMode.SPECIFIC, configuration.scopeMode());
        assertEquals(1, configuration.rootCount());
        assertEquals("contoso.sharepoint.com", configuration.tenantHost());
        var root = sharePoint.resolvedRoots(tenant, receipt.sourceId()).getFirst();
        assertEquals("site-1", root.siteId());
        assertEquals("drive-1", root.driveId(), "the library resolved by URL path, not by its display name");
        assertEquals("Tài liệu", root.displayName());
        assertEquals(1, attempts(receipt.sourceId()), "a new Source starts synchronizing");
        assertEquals("SUCCEEDED", operationStatus(receipt.operation().id()));
    }

    @Test
    void aPrivateSourceCanBeRequestedAndCreated() {
        // V77 renamed RESTRICTED to PRIVATE; the selection intent must accept the current name.
        when(session.libraries("site-1")).thenReturn(List.of(new SharePointProvider.Library("drive-1", "Documents",
                "/sites/Finance/Shared Documents")));
        var receipt = sources.create(owner, UUID.randomUUID(), "Finance", credential, scope(LIBRARY_URL),
                SourceAccess.PRIVATE, List.of());

        assertEquals(SharePointSelectionProcessor.Result.COMPLETED, processor.execute(claim(receipt.operation().id())));
        assertEquals("PRIVATE", jdbc.sql("SELECT access_type FROM connector_credential_pairs WHERE id = :id")
                .param("id", receipt.sourceId().value()).query(String.class).single());
    }

    @Test
    void aLibraryThatIsNotOnTheSiteFailsWithoutCreatingASource() {
        when(session.libraries("site-1")).thenReturn(List.of(new SharePointProvider.Library("drive-9", "Policies",
                "/sites/Finance/Policies")));
        var receipt = sources.create(owner, UUID.randomUUID(), "Finance", credential, scope(LIBRARY_URL),
                SourceAccess.PUBLIC, List.of());

        var result = processor.execute(claim(receipt.operation().id()));

        assertEquals(SharePointSelectionProcessor.Result.FAILED, result);
        assertEquals("SOURCE_SHAREPOINT_ROOT_URL_INVALID", operationError(receipt.operation().id()));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM sharepoint_sources WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Integer.class).single(), "nothing is stored");
        assertThrows(SourceException.class, () -> sources.configuration(owner, receipt.sourceId()));
    }

    @Test
    void repeatingARequestRecoversItsReceiptInsteadOfStartingAgain() {
        when(session.libraries("site-1")).thenReturn(List.of(new SharePointProvider.Library("drive-1", "Tài liệu",
                "/sites/Finance/Shared Documents")));
        UUID requestId = UUID.randomUUID();
        var first = sources.create(owner, requestId, "Finance", credential, scope(LIBRARY_URL), SourceAccess.PUBLIC, List.of());
        var again = sources.create(owner, requestId, "Finance", credential, scope(LIBRARY_URL), SourceAccess.PUBLIC, List.of());

        assertEquals(first.operation().id(), again.operation().id());
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM sharepoint_selection_operations WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query(Integer.class).single());
        assertEquals(first, sources.selectionRequest(owner, requestId));
    }

    @Test
    void replacingTheScopeNeedsTheCurrentRevisions() {
        when(session.libraries("site-1")).thenReturn(List.of(new SharePointProvider.Library("drive-1", "Tài liệu",
                "/sites/Finance/Shared Documents")));
        var created = sources.create(owner, UUID.randomUUID(), "Finance", credential, scope(LIBRARY_URL),
                SourceAccess.PUBLIC, List.of());
        processor.execute(claim(created.operation().id()));
        var configuration = sources.configuration(owner, created.sourceId());

        assertThrows(SourceException.class, () -> sources.replaceScope(owner, UUID.randomUUID(), created.sourceId(),
                configuration.scopeRevision() + 1, configuration.credentialRevision(), scope(LIBRARY_URL)));
        assertThrows(SourceException.class, () -> sources.replaceScope(owner, UUID.randomUUID(), created.sourceId(),
                configuration.scopeRevision(), configuration.credentialRevision() + 1, scope(LIBRARY_URL)));

        var receipt = sources.replaceScope(owner, UUID.randomUUID(), created.sourceId(), configuration.scopeRevision(),
                configuration.credentialRevision(), scope("https://contoso.sharepoint.com/sites/Finance"));
        assertEquals(created.sourceId(), receipt.sourceId());
        assertNotNull(sources.configuration(owner, created.sourceId()).pendingSelectionOperation());
    }

    private static Scope scope(String url) {
        return new Scope(ScopeMode.SPECIFIC, List.of(url), List.of(), List.of("*/Archive/*"), true, false, 30, 168);
    }

    /** Stands in for the relay, which stamps the delivery a worker then claims. */
    private SharePointSelectionProcessor.Work claim(SourceOperationId operation) {
        UUID delivery = UUID.randomUUID();
        jdbc.sql("""
                UPDATE sharepoint_selection_operations SET delivery_id = :delivery, dispatched_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND id = :id
                """).param("delivery", delivery).param("tenant", tenant.value())
                .param("id", operation.value()).update();
        return Objects.requireNonNull(tx.execute(_ -> selections.claim(tenant, operation, delivery).orElseThrow()));
    }

    private int attempts(SourceId source) {
        return jdbc.sql("SELECT COUNT(*) FROM source_sync_attempts WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).query(Integer.class).single();
    }

    private String operationStatus(SourceOperationId operation) {
        return jdbc.sql("SELECT status FROM sharepoint_selection_operations WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.value()).param("id", operation.value()).query(String.class).single();
    }

    private String operationError(SourceOperationId operation) {
        return jdbc.sql("SELECT error_code FROM sharepoint_selection_operations WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.value()).param("id", operation.value()).query(String.class).single();
    }

    private void seed() {
        jdbc.sql("INSERT INTO actors (id) VALUES (:id)").param("id", owner.value()).update();
        jdbc.sql("""
                INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference)
                VALUES (:id, 'sharepoint-scope', 'SharePoint scope', 'ACTIVE', 'TEST')
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
                VALUES (:id, :tenant, 'Entra app', 'SHAREPOINT_APP', 'ACTIVE', :actor)
                """).param("id", credential.value()).param("tenant", tenant.value()).param("actor", owner.value()).update();
        jdbc.sql("""
                INSERT INTO sharepoint_credentials (tenant_id, credential_id, directory_id, client_id, cloud,
                    auth_method, connection_status, secret_ciphertext, secret_nonce, secret_key_version)
                VALUES (:tenant, :credential, :directory, :client, 'GLOBAL', 'CLIENT_SECRET', 'ACTIVE',
                    :ciphertext, :nonce, 'v1')
                """).param("tenant", tenant.value()).param("credential", credential.value())
                .param("directory", UUID.randomUUID()).param("client", UUID.randomUUID())
                .param("ciphertext", new byte[32]).param("nonce", new byte[12]).update();
    }
}
