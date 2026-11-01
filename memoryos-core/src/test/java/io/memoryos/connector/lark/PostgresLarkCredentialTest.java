package io.memoryos.connector.lark;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkCredentialService;
import io.memoryos.connector.LarkException;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkProviderException;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.lark.persistence.JdbcLarkCredentialRepository;
import io.memoryos.connector.lark.persistence.LarkCredentialConfiguration;
import io.memoryos.shared.CredentialCipher;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamException;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresLarkCredentialTest {
    private static final String APP_ID = "cli_a1b2c3d4e5f6a7b8";
    private static final LarkGateway.Bot BOT = new LarkGateway.Bot("MemoryOS Test", "ou_bot");

    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private IamAuthorization authorization;
    private JdbcLarkCredentialRepository credentials;
    private LarkGateway provider;
    private LarkCredentialService service;
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
        jdbc.sql("INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference) VALUES (:id, 'lark', 'Lark', 'ACTIVE', 'TEST')")
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
        credentials = new JdbcLarkCredentialRepository(jdbc, new JdbcSourceRepository(jdbc, event -> { }), encryption());
        provider = mock(LarkGateway.class);
        service = service(credentials);
    }

    private LarkCredentialService service(JdbcLarkCredentialRepository repository) {
        return TestDatabase.transactionalProxy(
                new DefaultLarkCredentialService(repository, provider, authorization, manager, TestDatabase.noAudit()),
                LarkCredentialService.class, manager);
    }

    private static LarkCredentialConfiguration encryption() {
        return new LarkCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "lark-test-v1");
    }

    private LarkGateway.Session session(Set<String> missingScopes) {
        var session = mock(LarkGateway.Session.class);
        when(session.bot()).thenReturn(BOT);
        when(session.missingScopes()).thenReturn(missingScopes);
        return session;
    }

    // doReturn and doThrow, because stubbing again with when() would first call a stub that throws.
    private void larkAnswers(LarkGateway.Session session) {
        doReturn(session).when(provider).open(any());
    }

    private void larkFails(LarkProviderException.Failure failure) {
        doThrow(new LarkProviderException(failure)).when(provider).open(any());
    }

    private static LarkCredentialService.Draft draft(String name, String secret) {
        return new LarkCredentialService.Draft(name, LarkGateway.Region.LARK, APP_ID,
                secret.getBytes(StandardCharsets.UTF_8));
    }

    private CredentialId create(String name) {
        var session = session(Set.of());
        larkAnswers(session);
        try (var draft = draft(name, "secret-value")) {
            return service.create(owner, draft);
        }
    }

    @Test
    void createVerifiesWithLarkThenStoresTheEncryptedSecretAndTheBot() {
        var session = session(Set.of("wiki:wiki:readonly", "drive:drive:readonly"));
        larkAnswers(session);
        CredentialId id;
        try (var draft = draft("Lark app", "secret-value")) {
            id = service.create(owner, draft);
        }
        // A working credential with scopes still to grant is stored, and names them.
        var view = service.list(owner).getFirst();
        assertEquals("MemoryOS Test", view.botName());
        assertEquals(List.of("drive:drive:readonly", "wiki:wiki:readonly"), view.missingScopes());
        assertEquals("ACTIVE", view.status());
        var stored = credentials.readUsable(tenant, id);
        assertEquals(APP_ID, stored.appId());
        assertEquals(LarkGateway.Region.LARK, stored.region());
        assertEquals("ou_bot", stored.botOpenId());
        try (var credential = credentials.credential(tenant, stored)) {
            assertArrayEquals("secret-value".getBytes(StandardCharsets.UTF_8), credential.appSecret());
            assertEquals(APP_ID, credential.appId());
        }
        assertFalse(new String(stored.secretCiphertext(), StandardCharsets.ISO_8859_1).contains("secret-value"));
        // The ciphertext is bound to tenant, credential and purpose: none of them may be swapped.
        var cipher = new CredentialCipher(new byte[32], "lark-test-v1", "LARK_APP");
        var envelope = new CredentialCipher.EncryptedCredential(
                stored.secretCiphertext(), stored.secretNonce(), stored.secretKeyVersion());
        assertThrows(Exception.class, () -> cipher.decrypt(new TenantId(UUID.randomUUID()), id.value(), "app-secret", envelope));
        assertThrows(Exception.class, () -> cipher.decrypt(tenant, UUID.randomUUID(), "app-secret", envelope));
        assertThrows(Exception.class, () -> cipher.decrypt(tenant, id.value(), "client-secret", envelope));
        assertThrows(Exception.class, () -> new CredentialCipher(new byte[32], "lark-test-v1", "SHAREPOINT_APP")
                .decrypt(tenant, id.value(), "app-secret", envelope));
    }

    @Test
    void aRefusedCredentialStoresNothing() {
        larkFails(LarkProviderException.Failure.AUTHENTICATION);
        try (var draft = draft("Rejected", "wrong")) {
            assertEquals("SOURCE_LARK_CREDENTIAL_REJECTED",
                    assertThrows(LarkException.class, () -> service.create(owner, draft)).code());
        }
        larkFails(LarkProviderException.Failure.QUOTA);
        try (var draft = draft("Throttled", "secret")) {
            assertEquals("SOURCE_LARK_QUOTA",
                    assertThrows(LarkException.class, () -> service.create(owner, draft)).code());
        }
        larkFails(LarkProviderException.Failure.UNAVAILABLE);
        try (var draft = draft("Unreachable", "secret")) {
            assertEquals("SOURCE_LARK_UNAVAILABLE",
                    assertThrows(LarkException.class, () -> service.create(owner, draft)).code());
        }
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM credentials WHERE credential_kind = 'LARK_APP'")
                .query(Integer.class).single());
    }

    @Test
    void malformedDraftsAreRefusedBeforeLarkIsAsked() {
        for (String appId : List.of("", "app", "cli_UPPER123456", "cli_short")) {
            try (var draft = new LarkCredentialService.Draft("Bad", LarkGateway.Region.LARK, appId,
                    "secret".getBytes(StandardCharsets.UTF_8))) {
                assertEquals("SOURCE_LARK_APP_ID_INVALID",
                        assertThrows(LarkException.class, () -> service.create(owner, draft)).code(), appId);
            }
        }
        try (var draft = draft("No secret", "")) {
            assertEquals("SOURCE_LARK_SECRET_INVALID",
                    assertThrows(LarkException.class, () -> service.create(owner, draft)).code());
        }
        try (var draft = draft(" ", "secret")) {
            assertThrows(SourceException.class, () -> service.create(owner, draft));
        }
        verifyNoInteractions(provider);
    }

    @Test
    void revisionPreconditionsFenceRenameReplaceAndDelete() {
        CredentialId id = create("Versioned");
        assertThrows(SourceException.class, () -> service.rename(owner, id, 2, "Too new"));
        assertThrows(SourceException.class, () -> service.rename(owner, id, 0, "Too old"));
        service.rename(owner, id, 1, "Renamed");
        assertEquals("Renamed", credentials.readUsable(tenant, id).name());
        try (var draft = draft("Replaced", "new-secret")) {
            assertThrows(SourceException.class, () -> service.replaceSecret(owner, id, 2, draft));
        }
        try (var draft = draft("Replaced", "new-secret")) {
            assertEquals(2, service.replaceSecret(owner, id, 1, draft));
        }
        var stored = credentials.readUsable(tenant, id);
        try (var credential = credentials.credential(tenant, stored)) {
            assertArrayEquals("new-secret".getBytes(StandardCharsets.UTF_8), credential.appSecret());
        }
        // The secret of another app is not a replacement.
        try (var draft = new LarkCredentialService.Draft("Other app", LarkGateway.Region.LARK, "cli_ffffffffffffffff",
                "secret".getBytes(StandardCharsets.UTF_8))) {
            assertThrows(SourceException.class, () -> service.replaceSecret(owner, id, 2, draft));
        }
        assertThrows(SourceException.class, () -> service.delete(owner, id, 1));
        service.delete(owner, id, 2);
        assertTrue(credentials.lock(tenant, id).isEmpty());
    }

    @Test
    void anAttachedSourceBlocksDeletion() {
        CredentialId id = create("Attached");
        UUID sourceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO connectors (id, tenant_id, name, connector_type, status) VALUES (:id, :t, 'Source', 'FILE', 'ACTIVE')")
                .param("id", sourceId).param("t", tenant.value()).update();
        jdbc.sql("INSERT INTO connector_credential_pairs (id, tenant_id, connector_id, credential_id, access_type, status) VALUES (:id, :t, :id, :credential, 'PUBLIC', 'NOT_STARTED')")
                .param("id", sourceId).param("t", tenant.value()).param("credential", id.value()).update();
        assertThrows(SourceException.class, () -> service.delete(owner, id, 1));
        assertEquals(List.of(new SourceId(sourceId)), credentials.attachedSources(tenant, id));
        assertEquals(List.of("rename", "replace_secret", "test"), service.list(owner).getFirst().actions());
    }

    @Test
    void tenantIsolationAndScopedManagerVisibility() {
        CredentialId id = create("Owned");
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
        try (var draft = draft("Scoped credential", "secret")) {
            scopedId = service.create(scoped, draft);
        }
        assertEquals(List.of(scopedId), service.list(scoped).stream().map(LarkCredentialService.CredentialView::id).toList());
        assertEquals(2, service.list(owner).size());
    }

    @Test
    void aTestRecordsTheBotAndMarksARefusedCredential() {
        CredentialId id = create("Will be revoked");
        var renamed = mock(LarkGateway.Session.class);
        when(renamed.bot()).thenReturn(new LarkGateway.Bot("Renamed bot", "ou_bot"));
        when(renamed.missingScopes()).thenReturn(Set.of("im:chat:readonly"));
        larkAnswers(renamed);
        var result = service.test(owner, id);
        assertEquals("Renamed bot", result.botName());
        assertEquals(Set.of("im:chat:readonly"), result.missingScopes());
        assertEquals("Renamed bot", credentials.readUsable(tenant, id).botName());
        assertEquals(List.of("im:chat:readonly"), service.list(owner).getFirst().missingScopes());

        larkFails(LarkProviderException.Failure.AUTHENTICATION);
        assertEquals("SOURCE_LARK_CREDENTIAL_REJECTED",
                assertThrows(LarkException.class, () -> service.test(owner, id)).code());
        var stored = credentials.lock(tenant, id).orElseThrow();
        assertEquals("NEEDS_UPDATE", stored.status());
        assertFalse(stored.usable());
        assertEquals(2, stored.revision());
        assertEquals("SOURCE_LARK_NEEDS_UPDATE",
                assertThrows(LarkException.class, () -> service.test(owner, id)).code());
        // An unreachable Lark says nothing about the credential.
        CredentialId other = create("Stays active");
        larkFails(LarkProviderException.Failure.UNAVAILABLE);
        assertThrows(LarkException.class, () -> service.test(owner, other));
        assertTrue(credentials.readUsable(tenant, other).usable());
    }

    @Test
    void unconfiguredEncryptionFailsClosed() {
        var failing = service(new JdbcLarkCredentialRepository(jdbc, new JdbcSourceRepository(jdbc, event -> { }),
                new LarkCredentialConfiguration("", "")));
        try (var draft = draft("No key", "secret")) {
            assertEquals("SOURCE_LARK_NOT_CONFIGURED",
                    assertThrows(LarkException.class, () -> failing.create(owner, draft)).code());
        }
        verifyNoInteractions(provider);
    }

    @Test
    void membersWithoutManagementCannotMutate() {
        var member = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors (id) VALUES (:id)").param("id", member.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships (tenant_id, actor_id, role, status) VALUES (:tenant, :actor, 'MEMBER', 'ACTIVE')")
                .param("tenant", tenant.value()).param("actor", member.value()).update();
        try (var draft = draft("Denied", "secret")) {
            assertThrows(IamException.class, () -> service.create(member, draft));
        }
        assertThrows(IamException.class, () -> service.list(member));
    }
}
