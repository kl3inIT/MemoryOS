package io.memoryos.connector.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.connector.GoogleDriveConnectionService;
import io.memoryos.connector.GoogleDriveException;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.GoogleDriveServiceAccountService;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.persistence.GoogleDriveCredentialConfiguration;
import io.memoryos.connector.persistence.JdbcGoogleDriveCredentialRepository;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.connector.persistence.JdbcSourceSyncRepository;
import io.memoryos.iam.IamException;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
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
    private final List<GoogleDriveProvider.ServiceAccountCredential> opened = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        var transactions = new DataSourceTransactionManager(dataSource);
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
        serviceAccounts = new DefaultGoogleDriveServiceAccountService(credentials, provider, authorization, transactions);
        connections = TestDatabase.transactionalProxy(new DefaultGoogleDriveConnectionService(credentials, provider, transactions),
                GoogleDriveConnectionService.class, transactions);
        authorizations = TestDatabase.transactionalProxy(new DefaultGoogleDriveAuthorizationService(credentials, authorization,
                new SourceAccessPolicy(authorization, sources, new io.memoryos.iam.group.DefaultGroupScopeService(
                        new io.memoryos.iam.group.persistence.GroupInvariantRepository(jdbc),
                        new io.memoryos.iam.group.persistence.GroupProjectionRepository(jdbc)))),
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
        assertFalse(new String((byte[]) row.get("service_account_key_ciphertext"), java.nio.charset.StandardCharsets.ISO_8859_1)
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
