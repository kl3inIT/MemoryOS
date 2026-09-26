package io.memoryos.connector.googledrive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.connector.GoogleDriveException;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.GoogleDriveServiceAccountService;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.googledrive.persistence.GoogleDriveCredentialConfiguration;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleDriveCredentialRepository;
import io.memoryos.connector.googledrive.persistence.JdbcGoogleGroupRepository;
import io.memoryos.connector.source.SourceAccessPolicy;
import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository;
import io.memoryos.iam.IamException;
import io.memoryos.iam.group.DefaultGroupScopeService;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.persistence.GroupInvariantRepository;
import io.memoryos.iam.group.persistence.GroupProjectionRepository;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class GoogleDriveServiceAccountCredentialTest {
    private static final String ADMIN = "admin@example.com";
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TenantId tenant;
    private ActorId manager;
    private GoogleDriveProvider provider;
    private GoogleDriveProvider.Session session;
    private GoogleDriveServiceAccountService serviceAccounts;
    private GoogleDriveAuthorizationService authorizations;
    private GoogleDriveConnectionService connections;
    private JdbcGoogleGroupRepository groups;
    private DataSourceTransactionManager transactions;
    private final List<GoogleDriveProvider.ServiceAccountCredential> opened = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        tenant = new TenantId(UUID.randomUUID());
        manager = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference) VALUES (:id, 'drive-sa', 'Drive', 'ACTIVE', 'TEST')")
                .param("id", tenant.value()).update();
        member(manager);
        jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name,system_key) VALUES (:tenant,:tenant,'Admin','ADMIN')")
                .param("tenant", tenant.value()).update();
        jdbc.sql("INSERT INTO iam_group_capability_grants(tenant_id,group_id,capability) VALUES (:tenant,:tenant,'SYSTEM_ADMIN')")
                .param("tenant", tenant.value()).update();
        jdbc.sql("INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id) VALUES (:tenant,:tenant,:actor)")
                .param("tenant", tenant.value()).param("actor", manager.value()).update();
        var authorization = new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), new IamLockRepository(jdbc));
        var sources = new JdbcSourceRepository(jdbc, event -> { });
        groups = new JdbcGoogleGroupRepository(jdbc);
        var credentials = new JdbcGoogleDriveCredentialRepository(jdbc, sources,
                new GoogleDriveCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "test-v1"),
                new JdbcSourceDocumentRepository(jdbc), new JdbcSourceSyncRepository(jdbc));
        session = mock(GoogleDriveProvider.Session.class);
        when(session.metadata("root")).thenReturn(new GoogleDriveProvider.FileMetadata("my-drive-root", "My Drive",
                "application/vnd.google-apps.folder", "1", null, null, false, List.of(), null, null));
        when(session.directoryUser(anyString())).thenReturn(new GoogleDriveProvider.DirectoryUser(ADMIN, true, false));
        provider = mock(GoogleDriveProvider.class);
        when(provider.open(any())).thenAnswer(invocation -> {
            var credential = (GoogleDriveProvider.ServiceAccountCredential) invocation.getArgument(0);
            opened.add(credential);
            return session;
        });
        serviceAccounts = new DefaultGoogleDriveServiceAccountService(credentials, provider, authorization, groups, transactions);
        connections = TestDatabase.transactionalProxy(new GoogleDriveConnectionService(credentials, provider, transactions),
                GoogleDriveConnectionService.class, transactions);
        authorizations = TestDatabase.transactionalProxy(new DefaultGoogleDriveAuthorizationService(credentials, authorization,
                new SourceAccessPolicy(authorization, sources, new DefaultGroupScopeService(
                        new GroupInvariantRepository(jdbc),
                        new GroupProjectionRepository(jdbc)), TestDatabase.noAudit()),
                        groups, TestDatabase.noAudit()),
                GoogleDriveAuthorizationService.class, transactions);
    }

    @AfterEach
    void closeDatabase() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void createsAnOwnerlessEncryptedCredentialThatActsAsTheValidatedAdmin() throws Exception {
        String keyJson = keyJson("1045");
        var credential = serviceAccounts.create(manager, " Workspace ", keyJson, " Admin@Example.com ");

        var row = jdbc.sql("""
                SELECT c.credential_kind, c.owner_actor_id, g.auth_method, g.account_email, g.service_account_email,
                    g.account_subject, g.service_account_key_ciphertext FROM credentials c
                JOIN google_drive_credentials g ON g.tenant_id=c.tenant_id AND g.credential_id=c.id WHERE c.id=:id
                """).param("id", credential.value()).query().singleRow();
        assertEquals("GOOGLE_SERVICE_ACCOUNT", row.get("credential_kind"));
        assertNull(row.get("owner_actor_id"));
        assertEquals("SERVICE_ACCOUNT", row.get("auth_method"));
        assertEquals(ADMIN, row.get("account_email"));
        assertEquals("indexer@memoryos-prod.iam.gserviceaccount.com", row.get("service_account_email"));
        assertEquals("1045", row.get("account_subject"));
        assertFalse(new String((byte[]) row.get("service_account_key_ciphertext"), StandardCharsets.ISO_8859_1)
                .contains("PRIVATE KEY"));

        var view = authorizations.list(manager).getFirst();
        assertEquals("Workspace", view.name());
        assertEquals("SERVICE_ACCOUNT", view.authMethod());
        assertEquals("indexer@memoryos-prod.iam.gserviceaccount.com", view.serviceAccountEmail());
        assertEquals(List.of("replace_key", "revoke", "delete"), view.actions());

        try (var connection = connections.openCredential(tenant, credential)) {
            assertEquals(1, connection.credentialRevision());
        }
        assertEquals(2, opened.size());
        assertEquals(List.of(ADMIN, ADMIN), opened.stream().map(GoogleDriveProvider.ServiceAccountCredential::subject).toList());
        assertThrows(SourceException.class, () -> authorizations.prepare(manager, "OAuth", credential, 1L, null));
    }

    @Test
    void refusedDelegationAndNonAdminAccountsStoreNothing() throws Exception {
        when(provider.open(any())).thenThrow(new GoogleDriveProviderException(GoogleDriveProviderException.Failure.AUTHENTICATION));
        assertEquals("GOOGLE_DRIVE_SERVICE_ACCOUNT_DELEGATION_MISSING", assertThrows(GoogleDriveException.class,
                () -> serviceAccounts.create(manager, "Workspace", keyJson("1045"), ADMIN)).code());

        doReturn(session).when(provider).open(any());
        when(session.directoryUser(anyString())).thenReturn(new GoogleDriveProvider.DirectoryUser(ADMIN, false, false));
        assertEquals("GOOGLE_DRIVE_SERVICE_ACCOUNT_ADMIN_REQUIRED", assertThrows(GoogleDriveException.class,
                () -> serviceAccounts.create(manager, "Workspace", keyJson("1045"), ADMIN)).code());

        when(session.directoryUser(anyString())).thenThrow(new GoogleDriveProviderException(GoogleDriveProviderException.Failure.ACCESS_DENIED));
        assertEquals("GOOGLE_DRIVE_SERVICE_ACCOUNT_ADMIN_REQUIRED", assertThrows(GoogleDriveException.class,
                () -> serviceAccounts.create(manager, "Workspace", keyJson("1045"), ADMIN)).code());

        assertThrows(SourceException.class, () -> serviceAccounts.create(manager, "Workspace", keyJson("1045"), "not-an-email"));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM google_drive_credentials").query(Long.class).single());
    }

    @Test
    void onlyGlobalSourceManagersHoldServiceAccounts() throws Exception {
        var plain = new ActorId(UUID.randomUUID());
        member(plain);
        assertThrows(IamException.class, () -> serviceAccounts.create(plain, "Workspace", keyJson("1045"), ADMIN));
        verifyNoInteractions(provider);
    }

    @Test
    void replacementKeepsTheServiceAccountAndRevocationDestroysTheKey() throws Exception {
        var credential = serviceAccounts.create(manager, "Workspace", keyJson("1045"), ADMIN);
        assertThrows(SourceException.class, () -> serviceAccounts.replace(manager, credential, 1, "Other", keyJson("2090"), ADMIN));
        assertThrows(SourceException.class, () -> serviceAccounts.replace(manager, credential, 7, "Workspace", keyJson("1045"), ADMIN));

        assertEquals(2, serviceAccounts.replace(manager, credential, 1, "Rotated", keyJson("1045"), ADMIN));
        assertEquals("Rotated", authorizations.list(manager).getFirst().name());

        assertArrayEquals(new byte[0], authorizations.disconnect(manager, credential, 2));
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM google_drive_credentials WHERE credential_id=:id AND service_account_key_ciphertext IS NOT NULL
                """).param("id", credential.value()).query(Long.class).single());
        assertEquals("GOOGLE_DRIVE_NEEDS_REAUTHORIZATION", assertThrows(GoogleDriveException.class,
                () -> connections.openCredential(tenant, credential)).code());
    }

    @Test
    void groupSyncReadsOnePagePerStepAndPromotesOnlyACompletedGeneration() throws Exception {
        var credential = serviceAccounts.create(manager, "Workspace", keyJson("1045"), ADMIN);
        when(session.groups("example.com", null)).thenReturn(new GoogleDriveProvider.DirectoryPage(
                List.of("eng@example.com", "all@example.com"), "groups-2"));
        when(session.groups("example.com", "groups-2")).thenReturn(new GoogleDriveProvider.DirectoryPage(
                List.of("gone@example.com"), null));
        when(session.groupMembers("all@example.com", null)).thenReturn(new GoogleDriveProvider.MemberPage(List.of(), true, null));
        when(session.groupMembers("eng@example.com", null)).thenReturn(new GoogleDriveProvider.MemberPage(
                List.of("a@example.com"), false, "members-2"));
        when(session.groupMembers("eng@example.com", "members-2")).thenReturn(new GoogleDriveProvider.MemberPage(
                List.of("b@example.com"), false, null));
        when(session.groupMembers("gone@example.com", null))
                .thenThrow(new GoogleDriveProviderException(GoogleDriveProviderException.Failure.NOT_FOUND));
        var synchronizer = new GoogleGroupSynchronizer(groups, connections, transactions, Duration.ofHours(1));

        for (int page = 0; page < 6; page++) {
            assertTrue(synchronizer.advance(tenant, credential, 1, ADMIN, session));
            assertNull(activeGeneration(credential), "Membership stays inactive until the run completes");
        }
        assertTrue(synchronizer.advance(tenant, credential, 1, ADMIN, session));
        assertEquals(1L, activeGeneration(credential));
        assertEquals(List.of("eng@example.com:a@example.com", "eng@example.com:b@example.com"), jdbc.sql("""
                SELECT group_email || ':' || member_email FROM google_group_members ORDER BY 1
                """).query(String.class).list());
        assertEquals(List.of("all@example.com"), jdbc.sql(
                "SELECT group_email FROM google_group_sync_groups WHERE whole_domain").query(String.class).list());
        assertFalse(synchronizer.advance(tenant, credential, 1, ADMIN, session), "Nothing is due within the interval");
        assertFalse(synchronizer.advance(tenant, credential, 2, ADMIN, session), "A stale credential revision reads nothing");
    }

    @Test
    void failedGroupSyncKeepsTheActiveGenerationAndRevocationForgetsIt() throws Exception {
        var credential = serviceAccounts.create(manager, "Workspace", keyJson("1045"), ADMIN);
        when(session.groups("example.com", null)).thenReturn(new GoogleDriveProvider.DirectoryPage(List.of("eng@example.com"), null));
        when(session.groupMembers("eng@example.com", null)).thenReturn(new GoogleDriveProvider.MemberPage(
                List.of("a@example.com"), false, null));
        var synchronizer = new GoogleGroupSynchronizer(groups, connections, transactions, Duration.ofHours(1));
        while (synchronizer.advance(tenant, credential, 1, ADMIN, session)) { }
        assertEquals(1L, activeGeneration(credential));

        jdbc.sql("UPDATE google_group_sync_runs SET started_at = started_at - INTERVAL '2 hours'").update();
        when(session.groups("example.com", null))
                .thenThrow(new GoogleDriveProviderException(GoogleDriveProviderException.Failure.ACCESS_DENIED));
        assertFalse(synchronizer.advance(tenant, credential, 1, ADMIN, session));
        assertEquals(1L, activeGeneration(credential), "A failed run keeps the last successful membership");
        assertEquals(List.of("COMPLETED:", "FAILED:SOURCE_GOOGLE_ACCESS_DENIED"), jdbc.sql("""
                SELECT status || ':' || COALESCE(error_code, '') FROM google_group_sync_runs ORDER BY generation
                """).query(String.class).list());
        assertEquals(1L, jdbc.sql("SELECT COUNT(*) FROM google_group_members").query(Long.class).single());

        authorizations.disconnect(manager, credential, 1);
        assertNull(activeGeneration(credential));
        assertEquals(0L, jdbc.sql("SELECT COUNT(*) FROM google_group_sync_runs").query(Long.class).single());
    }

    private @Nullable Long activeGeneration(CredentialId credential) {
        return jdbc.sql("SELECT active_group_generation FROM google_drive_credentials WHERE credential_id=:id")
                .param("id", credential.value()).query((rs, _) -> rs.getObject(1, Long.class)).list().getFirst();
    }

    private void member(ActorId actor) {
        jdbc.sql("INSERT INTO actors (id) VALUES (:id)").param("id", actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships (tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')")
                .param("tenant", tenant.value()).param("actor", actor.value()).update();
    }

    private static String keyJson(String clientId) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\\n" + Base64.getEncoder().encodeToString(generator.generateKeyPair()
                .getPrivate().getEncoded()) + "\\n-----END PRIVATE KEY-----\\n";
        return "{\"type\":\"service_account\",\"private_key_id\":\"3f2a9c\",\"private_key\":\"" + pem
                + "\",\"client_email\":\"indexer@memoryos-prod.iam.gserviceaccount.com\",\"client_id\":\"" + clientId + "\"}";
    }
}
