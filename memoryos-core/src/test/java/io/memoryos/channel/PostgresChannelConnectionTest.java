package io.memoryos.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.BusinessException;
import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.channel.persistence.ChannelCredentialConfiguration;
import io.memoryos.channel.persistence.JdbcChannelConnectionRepository;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.InitialTenantBootstrapRequest;
import io.memoryos.iam.InitialTenantBootstrapper;
import io.memoryos.iam.group.DefaultIamAuthorization;
import io.memoryos.iam.group.GroupProvisioner;
import io.memoryos.iam.group.persistence.GroupCapabilityGrantRepository;
import io.memoryos.iam.group.persistence.GroupMembershipRepository;
import io.memoryos.iam.group.persistence.GroupRepository;
import io.memoryos.iam.group.persistence.IamAuthorizationRepository;
import io.memoryos.iam.group.persistence.IamLockRepository;
import io.memoryos.iam.identity.DefaultServiceAccounts;
import io.memoryos.iam.identity.persistence.JpaActorProfileRecorder;
import io.memoryos.iam.identity.persistence.JpaExternalIdentityRegistry;
import io.memoryos.iam.tenant.DefaultInitialTenantBootstrapper;
import io.memoryos.iam.tenant.persistence.JpaTenantMembershipProvisioner;
import io.memoryos.iam.tenant.persistence.JpaTenantRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresChannelConnectionTest {
    private static final TenantId TENANT = new TenantId(UUID.fromString("10000000-0000-0000-0000-000000000247"));
    private static final String ISSUER = "https://keycloak.example/realms/memoryos";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String SECRET = "lark-app-secret-for-the-test";

    private JpaHarness jpa;
    private JdbcClient jdbc;
    private ChannelConnectionService channels;
    private ActorId owner;
    private ActorId member;

    @BeforeEach
    void setUp() throws SQLException {
        var dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        jpa = TestDatabase.jpa(dataSource);
        var tenants = new JpaTenantRepository(jpa.entityManager());
        var identities = new JpaExternalIdentityRegistry(jpa.entityManager());
        var locks = new IamLockRepository(jdbc);
        var groups = new GroupProvisioner(
                new GroupRepository(jpa.entityManager()),
                new GroupMembershipRepository(jpa.entityManager()),
                new GroupCapabilityGrantRepository(jpa.entityManager())
        );
        owner = TestDatabase.transactionalProxy(
                new DefaultInitialTenantBootstrapper(tenants, locks, identities, identities, groups, event -> { }),
                InitialTenantBootstrapper.class,
                jpa.transactionManager()
        ).bootstrap(new InitialTenantBootstrapRequest(
                TENANT, new ExternalIdentity(ISSUER, "owner"), "tasco", "Tasco", "TEST-MEM-246-CHANNEL"
        )).ownerActorId();
        var memberships = new JpaTenantMembershipProvisioner(tenants);
        member = new TransactionTemplate(jpa.transactionManager()).execute(_ -> {
            ActorId actor = identities.resolveOrCreate(new ExternalIdentity(ISSUER, "member"));
            memberships.grantMember(TENANT, actor);
            groups.addToBasicGroup(TENANT, actor);
            return actor;
        });
        var accounts = new DefaultServiceAccounts(tenants, locks, identities, new JpaActorProfileRecorder(jpa.entityManager()),
                memberships, groups);
        channels = TestDatabase.transactionalProxy(new ChannelConnectionService(
                        new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), locks),
                        new JdbcChannelConnectionRepository(jdbc),
                        new ChannelCredentialConfiguration(KEY, "test-1"),
                        accounts,
                        TestDatabase.audit(jdbc, jpa.transactionManager())),
                ChannelConnectionService.class, jpa.transactionManager());
    }

    @AfterEach
    void closeJpa() {
        jpa.close();
    }

    private ChannelConnectionDraft draft(String appId) {
        return new ChannelConnectionDraft(ChannelProvider.LARK, " Trợ lý Lark ", ChannelRegion.LARK, appId, SECRET, "vi");
    }

    @Test
    void aBotStartsSwitchedOffWithItsOwnServiceAccountAndAnEncryptedSecret() {
        var bot = channels.create(owner, draft("cli_a1b2c3d4e5f6"));

        assertEquals("Trợ lý Lark", bot.name());
        assertFalse(bot.enabled());
        assertEquals(List.of(bot), channels.list(owner));
        assertEquals("SERVICE:ACTIVE:Trợ lý Lark", jdbc.sql("""
                        SELECT a.account_type || ':' || m.status || ':' || p.display_name
                        FROM actors a JOIN tenant_memberships m ON m.actor_id = a.id
                        JOIN actor_profiles p ON p.actor_id = a.id WHERE a.id = :account
                        """).param("account", bot.serviceAccount().value()).query(String.class).single());
        // The secret is stored only as its envelope.
        byte[] stored = jdbc.sql("SELECT secret_ciphertext FROM channel_connections WHERE id = :id")
                .param("id", bot.id()).query(byte[].class).single();
        assertFalse(new String(stored, StandardCharsets.UTF_8).contains(SECRET));
        assertEquals(List.of("channel.create"), actions());
    }

    @Test
    void oneAppServesOneBotAndTwoAppsMakeTwoBots() {
        channels.create(owner, draft("cli_a1b2c3d4e5f6"));
        assertEquals("CHANNEL_APP_IN_USE", code(() -> channels.create(owner, draft("cli_a1b2c3d4e5f6"))));
        var second = channels.create(owner, draft("cli_f6e5d4c3b2a1"));
        assertEquals(2, channels.list(owner).size());
        assertNotEquals(channels.list(owner).getFirst().serviceAccount(), second.serviceAccount());
    }

    @Test
    void switchingAndReplacingTheSecretFollowTheRevision() {
        var bot = channels.create(owner, draft("cli_a1b2c3d4e5f6"));
        var on = channels.setEnabled(owner, bot.id(), bot.revision(), true);
        assertTrue(on.enabled());
        assertEquals("CHANNEL_CHANGED", code(() -> channels.setEnabled(owner, bot.id(), bot.revision(), false)));
        var replaced = channels.replaceSecret(owner, bot.id(), on.revision(), "another-secret");
        assertEquals(on.revision() + 1, replaced.revision());
        assertEquals("CHANNEL_NOT_FOUND", code(() -> channels.setEnabled(owner, UUID.randomUUID(), 0, true)));
    }

    @Test
    void removingABotRetiresItsAccountAndKeepsItsName() {
        var bot = channels.create(owner, draft("cli_a1b2c3d4e5f6"));
        channels.delete(owner, bot.id(), bot.revision());
        assertTrue(channels.list(owner).isEmpty());
        assertEquals("INACTIVE:Trợ lý Lark", jdbc.sql("""
                        SELECT m.status || ':' || p.display_name FROM tenant_memberships m
                        JOIN actor_profiles p ON p.actor_id = m.actor_id WHERE m.actor_id = :account
                        """).param("account", bot.serviceAccount().value()).query(String.class).single());
        assertEquals(List.of("channel.create", "channel.delete"), actions());
    }

    @Test
    void inputIsCheckedAndOnlyAHolderOfTheCapabilityManagesBots() {
        assertEquals("CHANNEL_INVALID", code(() -> channels.create(owner, draft("app-without-prefix"))));
        assertEquals("CHANNEL_INVALID", code(() -> channels.create(owner,
                new ChannelConnectionDraft(ChannelProvider.LARK, " ", ChannelRegion.LARK, "cli_a1b2c3d4e5f6", SECRET, "vi"))));
        assertEquals("CHANNEL_INVALID", code(() -> channels.create(owner,
                new ChannelConnectionDraft(ChannelProvider.LARK, "Bot", ChannelRegion.LARK, "cli_a1b2c3d4e5f6", SECRET, "fr"))));
        assertThrows(BusinessException.class, () -> channels.list(member));
        assertThrows(BusinessException.class, () -> channels.create(member, draft("cli_a1b2c3d4e5f6")));
        assertTrue(jdbc.sql("SELECT count(*) FROM channel_connections").query(Long.class).single() == 0);
    }

    @Test
    void aMissingKeyFailsTheBotsAlone() {
        var unconfigured = new ChannelCredentialConfiguration("", "");
        assertEquals("CHANNEL_NOT_CONFIGURED", assertThrows(ChannelException.class,
                () -> unconfigured.cipher(ChannelProvider.LARK)).code());
    }

    private List<String> actions() {
        return jdbc.sql("SELECT action FROM audit_event WHERE action LIKE 'channel.%' ORDER BY occurred_at, id")
                .query(String.class).list();
    }

    private static String code(Runnable call) {
        return assertThrows(BusinessException.class, call::run).code();
    }
}
