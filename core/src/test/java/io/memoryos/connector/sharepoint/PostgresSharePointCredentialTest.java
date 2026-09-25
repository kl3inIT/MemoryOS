package io.memoryos.connector.sharepoint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointCredentialService;
import io.memoryos.connector.SharePointException;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointCredentialRepository;
import io.memoryos.connector.source.persistence.CredentialCipher;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.connector.sharepoint.persistence.SharePointCredentialConfiguration;
import io.memoryos.iam.IamException;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
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
class PostgresSharePointCredentialTest {
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private IamAuthorization authorization;
    private JdbcSharePointCredentialRepository credentials;
    private SharePointProvider provider;
    private SharePointCredentialService service;
    private TenantId tenant;
    private ActorId owner;
    private DataSourceTransactionManager manager;

    @AfterEach
    void closeDatabase() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        manager = new DataSourceTransactionManager(dataSource);
        tenant = new TenantId(UUID.randomUUID());
        owner = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors (id) VALUES (:id)").param("id", owner.value()).update();
        jdbc.sql("INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference) VALUES (:id, 'sharepoint', 'SharePoint', 'ACTIVE', 'TEST')")
                .param("id", tenant.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships (tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')")
                .param("tenant", tenant.value()).param("actor", owner.value()).update();
        jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name,system_key) VALUES (:tenant,:tenant,'Admin','ADMIN')")
                .param("tenant", tenant.value()).update();
        jdbc.sql("INSERT INTO iam_group_capability_grants(tenant_id,group_id,capability) VALUES (:tenant,:tenant,'SYSTEM_ADMIN')")
                .param("tenant", tenant.value()).update();
        jdbc.sql("INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id) VALUES (:tenant,:tenant,:actor)")
                .param("tenant", tenant.value()).param("actor", owner.value()).update();
        authorization = new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), new IamLockRepository(jdbc));
        var sources = new JdbcSourceRepository(jdbc, event -> { });
        credentials = new JdbcSharePointCredentialRepository(jdbc, sources, sharePointEncryption());
        provider = mock(SharePointProvider.class);
        service = TestDatabase.transactionalProxy(
                new DefaultSharePointCredentialService(credentials, provider, authorization, manager, TestDatabase.noAudit()),
                SharePointCredentialService.class, manager);
    }

    private static SharePointCredentialConfiguration sharePointEncryption() {
        return new SharePointCredentialConfiguration(
                Base64.getEncoder().encodeToString(new byte[32]), "sp-test-v1");
    }

    private SharePointProvider.Session session() {
        var session = mock(SharePointProvider.Session.class);
        when(session.root()).thenReturn(new SharePointProvider.RootSite(
                "site-id", "https://tenant.sharepoint.com", "tenant.sharepoint.com"));
        return session;
    }

    private SharePointCredentialService.Draft secretDraft(String name) {
        return new SharePointCredentialService.Draft(name, UUID.randomUUID(), UUID.randomUUID(),
                SharePointProvider.Cloud.GLOBAL, SharePointProvider.AuthMethod.CLIENT_SECRET,
                "secret-value".getBytes(StandardCharsets.UTF_8), null, null);
    }

    @Test
    void createVerifiesWithMicrosoftThenStoresEncryptedSecret() {
        var session = session();
        when(provider.open(any())).thenReturn(session);
        CredentialId id;
        try (var draft = secretDraft("Entra app")) {
            id = service.create(owner, draft);
        }
        var stored = credentials.readUsable(tenant, id);
        assertEquals("tenant.sharepoint.com", stored.tenantHost());
        assertNotNull(stored.secretCiphertext());
        try (var authentication = credentials.authentication(tenant, stored)) {
            assertArrayEquals("secret-value".getBytes(StandardCharsets.UTF_8), authentication.clientSecret());
        }
        // The ciphertext is bound to tenant, credential and purpose: none of them may be swapped.
        var otherTenant = new TenantId(UUID.randomUUID());
        assertThrows(Exception.class, () -> new CredentialCipher(
                new byte[32], "sp-test-v1", "SHAREPOINT_APP").decrypt(otherTenant, id.value(), "client-secret",
                new CredentialCipher.EncryptedCredential(
                        stored.secretCiphertext(), stored.secretNonce(), stored.secretKeyVersion())));
        assertThrows(Exception.class, () -> new CredentialCipher(
                new byte[32], "sp-test-v1", "SHAREPOINT_APP").decrypt(tenant, UUID.randomUUID(), "client-secret",
                new CredentialCipher.EncryptedCredential(
                        stored.secretCiphertext(), stored.secretNonce(), stored.secretKeyVersion())));
        assertThrows(Exception.class, () -> new CredentialCipher(
                new byte[32], "sp-test-v1", "SHAREPOINT_APP").decrypt(tenant, id.value(), "private-key",
                new CredentialCipher.EncryptedCredential(
                        stored.secretCiphertext(), stored.secretNonce(), stored.secretKeyVersion())));
    }

    @Test
    void rejectedCredentialStoresNothing() {
        when(provider.open(any())).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.AUTHENTICATION,
                SharePointProviderException.Reason.INVALID_CLIENT_SECRET));
        try (var draft = secretDraft("Rejected")) {
            var failure = assertThrows(SharePointException.class, () -> service.create(owner, draft));
            assertEquals("SOURCE_SHAREPOINT_CREDENTIAL_SECRET_REJECTED", failure.code());
        }
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM credentials WHERE credential_kind = 'SHAREPOINT_APP'")
                .query(Integer.class).single());
    }

    @Test
    void revisionPreconditionsFenceRenameReplaceAndDelete() {
        var session = session();
        when(provider.open(any())).thenReturn(session);
        CredentialId id;
        try (var draft = secretDraft("Versioned")) {
            id = service.create(owner, draft);
        }
        var row = credentials.readUsable(tenant, id);
        var directoryId = row.directoryId();
        var clientId = row.clientId();
        assertThrows(SourceException.class, () -> service.rename(owner, id, 2, "Too new"));
        assertThrows(SourceException.class, () -> service.rename(owner, id, 0, "Too old"));
        service.rename(owner, id, 1, "Renamed");
        assertEquals("Renamed", credentials.readUsable(tenant, id).name());
        try (var draft = new SharePointCredentialService.Draft("Replaced", directoryId, clientId,
                SharePointProvider.Cloud.GLOBAL, SharePointProvider.AuthMethod.CLIENT_SECRET,
                "new-secret".getBytes(StandardCharsets.UTF_8), null, null)) {
            assertThrows(SourceException.class, () -> service.replaceAuthentication(owner, id, 2, draft));
        }
        try (var draft = new SharePointCredentialService.Draft("Replaced", directoryId, clientId,
                SharePointProvider.Cloud.GLOBAL, SharePointProvider.AuthMethod.CLIENT_SECRET,
                "new-secret".getBytes(StandardCharsets.UTF_8), null, null)) {
            assertEquals(2, service.replaceAuthentication(owner, id, 1, draft));
        }
        assertThrows(SourceException.class, () -> service.delete(owner, id, 1));
        service.delete(owner, id, 2);
        assertTrue(credentials.lock(tenant, id).isEmpty());
    }

    @Test
    void attachedSourceBlocksDeletion() {
        var session = session();
        when(provider.open(any())).thenReturn(session);
        CredentialId id;
        try (var draft = secretDraft("Attached")) {
            id = service.create(owner, draft);
        }
        UUID sourceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO connectors (id, tenant_id, name, connector_type, status) VALUES (:id, :t, 'Source', 'FILE', 'ACTIVE')")
                .param("id", sourceId).param("t", tenant.value()).update();
        jdbc.sql("INSERT INTO connector_credential_pairs (id, tenant_id, connector_id, credential_id, access_type, status) VALUES (:id, :t, :id, :credential, 'PUBLIC', 'NOT_STARTED')")
                .param("id", sourceId).param("t", tenant.value()).param("credential", id.value()).update();
        assertThrows(SourceException.class, () -> service.delete(owner, id, 1));
        assertEquals(List.of(new SourceId(sourceId)), credentials.attachedSources(tenant, id));
    }

    @Test
    void tenantIsolationAndScopedManagerVisibility() {
        var session = session();
        when(provider.open(any())).thenReturn(session);
        CredentialId id;
        try (var draft = secretDraft("Owned")) {
            id = service.create(owner, draft);
        }
        var otherTenant = new TenantId(UUID.randomUUID());
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        jdbc.sql("INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference) VALUES (:id, 'other', 'Other', 'ACTIVE', 'TEST')")
                .param("id", otherTenant.value()).update();
        assertTrue(credentials.lock(otherTenant, id).isEmpty());
        assertTrue(credentials.list(otherTenant, null).isEmpty());
        // A scoped manager sees only credentials they own.
        var scoped = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors (id) VALUES (:id)").param("id", scoped.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships (tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')")
                .param("tenant", tenant.value()).param("actor", scoped.value()).update();
        var group = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name) VALUES (:tenant,:group,'Scoped')")
                .param("tenant", tenant.value()).param("group", group).update();
        jdbc.sql("INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id,is_manager) VALUES (:tenant,:group,:actor,TRUE)")
                .param("tenant", tenant.value()).param("group", group).param("actor", scoped.value()).update();
        assertTrue(service.list(scoped).isEmpty());
        assertThrows(SourceException.class, () -> service.rename(scoped, id, 1, "Not mine"));
        CredentialId scopedId;
        try (var draft = secretDraft("Scoped credential")) {
            scopedId = service.create(scoped, draft);
        }
        assertEquals(List.of(scopedId), service.list(scoped).stream().map(SharePointCredentialService.CredentialView::id).toList());
        assertThrows(SourceException.class, () -> service.rename(scoped, id, 1, "Still not mine"));
    }

    @Test
    void testMarksRejectedCredentialNeedsUpdate() {
        var session = session();
        when(provider.open(any())).thenReturn(session);
        CredentialId id;
        try (var draft = secretDraft("Will expire")) {
            id = service.create(owner, draft);
        }
        when(provider.open(any())).thenThrow(new SharePointProviderException(
                SharePointProviderException.Failure.AUTHENTICATION,
                SharePointProviderException.Reason.EXPIRED_CLIENT_SECRET));
        var failure = assertThrows(SharePointException.class, () -> service.test(owner, id));
        assertEquals("SOURCE_SHAREPOINT_CREDENTIAL_SECRET_EXPIRED", failure.code());
        var stored = credentials.lock(tenant, id).orElseThrow();
        assertEquals("NEEDS_UPDATE", stored.status());
        assertFalse(stored.usable());
        assertThrows(SharePointException.class, () -> service.test(owner, id));
    }

    @Test
    void unconfiguredEncryptionFailsClosed() {
        var unconfigured = new JdbcSharePointCredentialRepository(jdbc,
                new JdbcSourceRepository(jdbc, event -> { }),
                new SharePointCredentialConfiguration("", ""));
        var failing = TestDatabase.transactionalProxy(
                new DefaultSharePointCredentialService(unconfigured, provider, authorization, manager, TestDatabase.noAudit()),
                SharePointCredentialService.class, manager);
        try (var draft = secretDraft("No key")) {
            var failure = assertThrows(SharePointException.class, () -> failing.create(owner, draft));
            assertEquals("SOURCE_SHAREPOINT_NOT_CONFIGURED", failure.code());
        }
        verifyNoInteractions(provider);
    }

    @Test
    void membersWithoutManagementCannotMutate() {
        var member = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors (id) VALUES (:id)").param("id", member.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships (tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')")
                .param("tenant", tenant.value()).param("actor", member.value()).update();
        try (var draft = secretDraft("Denied")) {
            assertThrows(IamException.class, () -> service.create(member, draft));
        }
        assertThrows(IamException.class, () -> service.list(member));
    }
}
