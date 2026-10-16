package io.memoryos.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.memoryos.BusinessException;
import io.memoryos.TestDatabase;
import io.memoryos.TestDatabase.JpaHarness;
import io.memoryos.channel.persistence.ChannelCredentialConfiguration;
import io.memoryos.channel.persistence.JdbcChannelConnectionRepository;
import io.memoryos.chat.ChatChannelBindings;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatPersonaService;
import io.memoryos.chat.PersonaPermissions;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final Platform platform = new Platform();
    private final ChatPersonaService personas = mock(ChatPersonaService.class);
    private ChatChannelBindings bindings;
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
        bindings = new ChatChannelBindings(new JdbcChatRepository(jdbc));
        channels = TestDatabase.transactionalProxy(new ChannelConnectionService(
                        new DefaultIamAuthorization(new IamAuthorizationRepository(jdbc), locks),
                        new JdbcChannelConnectionRepository(jdbc),
                        new ChannelCredentialConfiguration(KEY, "test-1"),
                        accounts,
                        TestDatabase.audit(jdbc, jpa.transactionManager()),
                        new ChannelAdapters(List.of(platform)),
                        jpa.transactionManager(), personas, bindings),
                ChannelConnectionService.class, jpa.transactionManager());
    }

    @AfterEach
    void closeJpa() {
        jpa.close();
    }

    private ChannelConnectionDraft draft(String appId) {
        return new ChannelConnectionDraft(ChannelProvider.LARK, " Trợ lý Lark ", lark(appId), "vi");
    }

    private static ChannelCredentials lark(String appId) {
        return new ChannelCredentials(appId, SECRET, Map.of(ChannelRegion.SETTING, "LARK"));
    }

    @Test
    void aBotStartsSwitchedOffWithItsOwnServiceAccountAndAnEncryptedSecret() {
        var bot = channels.create(owner, draft("cli_a1b2c3d4e5f6"));

        assertEquals("Trợ lý Lark", bot.name());
        assertEquals(Map.of("region", "LARK"), bot.settings());
        assertEquals(Map.of("region", "LARK"), channels.get(owner, bot.id()).settings());
        assertFalse(bot.enabled());
        assertEquals("MemoryOS Bot", bot.botName());
        assertEquals("ou_bot", bot.botExternalId());
        assertEquals(List.of("contact:user.email:readonly"), bot.missingScopes());
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
    void aSettingChangesWhatTheProviderAcceptsAndMovesTheRevision() {
        var bot = channels.create(owner, draft("cli_a1b2c3d4e5f6"));
        var changed = channels.updateSettings(owner, bot.id(), bot.revision(), Map.of(ChannelRegion.SETTING, "FEISHU"));
        assertEquals(Map.of("region", "FEISHU"), changed.settings());
        assertEquals(bot.revision() + 1, changed.revision());
        assertEquals("CHANNEL_CHANGED", code(() -> channels.updateSettings(owner, bot.id(), bot.revision(),
                Map.of(ChannelRegion.SETTING, "LARK"))));
        assertEquals("CHANNEL_INVALID", code(() -> channels.updateSettings(owner, bot.id(), changed.revision(),
                Map.of("transport", "POLLING"))));
        assertEquals(Map.of("region", "FEISHU"), channels.get(owner, bot.id()).settings());
        assertEquals("CHANNEL_NOT_FOUND", code(() -> channels.updateSettings(owner, UUID.randomUUID(), 0, Map.of())));
    }

    /** MEM-263: a Zalo bot's row is its number and how it receives; the database refuses anything else. */
    @Test
    void theDatabaseHoldsAZaloBotByItsNumberAndHowItReceives() {
        var bot = channels.create(owner, draft("cli_a1b2c3d4e5f6"));
        jdbc.sql("UPDATE channel_connections SET provider = 'ZALO_BOT', app_id = '1234567890', "
                + "settings = '{\"transport\": \"POLLING\"}' WHERE id = :id").param("id", bot.id()).update();
        assertEquals("ZALO_BOT", jdbc.sql("SELECT provider FROM channel_connections WHERE id = :id")
                .param("id", bot.id()).query(String.class).single());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                "UPDATE channel_connections SET settings = '{\"transport\": \"CARRIER_PIGEON\"}' WHERE id = :id")
                .param("id", bot.id()).update());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql(
                "UPDATE channel_connections SET app_id = 'cli_a1b2c3d4e5f6' WHERE id = :id")
                .param("id", bot.id()).update());
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
        assertEquals("CHANNEL_INVALID", code(() -> channels.create(owner,
                new ChannelConnectionDraft(ChannelProvider.LARK, " ", lark("cli_a1b2c3d4e5f6"), "vi"))));
        assertEquals("CHANNEL_INVALID", code(() -> channels.create(owner,
                new ChannelConnectionDraft(ChannelProvider.LARK, "Bot", lark("cli_a1b2c3d4e5f6"), "fr"))));
        assertEquals("CHANNEL_INVALID", code(() -> channels.create(owner, new ChannelConnectionDraft(ChannelProvider.LARK,
                "Bot", new ChannelCredentials("cli_a1b2c3d4e5f6", " ", Map.of()), "vi"))));
        assertThrows(BusinessException.class, () -> channels.list(member));
        assertThrows(BusinessException.class, () -> channels.create(member, draft("cli_a1b2c3d4e5f6")));
        assertTrue(jdbc.sql("SELECT count(*) FROM channel_connections").query(Long.class).single() == 0);
    }

    @Test
    void anAppThePlatformRefusesIsNotStoredAndATestStoresNothing() {
        platform.refusing = true;
        assertEquals("CHANNEL_APP_REJECTED", code(() -> channels.create(owner, draft("cli_a1b2c3d4e5f6"))));
        assertEquals("CHANNEL_APP_REJECTED", code(() -> channels.test(owner, ChannelProvider.LARK,
                lark("cli_a1b2c3d4e5f6"))));
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM channel_connections").query(Long.class).single());

        platform.refusing = false;
        var bot = channels.create(owner, draft("cli_a1b2c3d4e5f6"));
        platform.refusing = true;
        assertEquals("CHANNEL_APP_REJECTED", code(() -> channels.replaceSecret(owner, bot.id(), bot.revision(), "wrong")));
        assertEquals(bot.revision(), channels.get(owner, bot.id()).revision());

        platform.refusing = false;
        assertEquals("MemoryOS Bot", channels.test(owner, ChannelProvider.LARK, lark("cli_f6e5d4c3b2a1"))
                .botName());
        assertEquals(1, channels.list(owner).size());
        assertThrows(BusinessException.class, () -> channels.test(member, ChannelProvider.LARK,
                lark("cli_f6e5d4c3b2a1")));
        assertEquals(List.of(SECRET, SECRET, SECRET, "wrong", SECRET), platform.secrets);
    }

    @Test
    void theAppIsTheOneThePlatformNamesAndASecretOfAnotherAppReplacesNothing() {
        platform.answeredAppId = "cli_normalised01";
        var bot = channels.create(owner, draft("cli_a1b2c3d4e5f6"));
        assertEquals("cli_normalised01", bot.appId());
        assertEquals("CHANNEL_APP_IN_USE", code(() -> channels.create(owner, draft("cli_f6e5d4c3b2a1"))));

        platform.answeredAppId = "cli_anotherapp01";
        assertEquals("CHANNEL_APP_REJECTED", code(() -> channels.replaceSecret(owner, bot.id(), bot.revision(), "other")));
        assertEquals(bot.revision(), channels.get(owner, bot.id()).revision());
    }

    @Test
    void theListCarriesWhatTheProcessThatHoldsABotLastWrote() {
        var bot = channels.create(owner, draft("cli_a1b2c3d4e5f6"));
        assertEquals(ChannelConnectionState.NONE, channels.listWithState(owner).getFirst().state());
        jdbc.sql("""
                        INSERT INTO channel_connection_state (connection_id, opened_at, heartbeat_at)
                        VALUES (:id, now(), now())
                        """).param("id", bot.id()).update();
        var listed = channels.listWithState(owner).getFirst();
        assertEquals(bot.id(), listed.connection().id());
        assertTrue(listed.state().openedAt() != null && listed.state().heartbeatAt() != null);
    }

    @Test
    void aMissingKeyFailsTheBotsAlone() {
        var unconfigured = new ChannelCredentialConfiguration("", "");
        assertEquals("CHANNEL_NOT_CONFIGURED", assertThrows(ChannelException.class,
                () -> unconfigured.cipher(ChannelProvider.LARK)).code());
    }

    @Test
    void aBotIsBoundToAnAssistantByWhoeverManagesBotsAndMayEditIt() {
        var bot = channels.create(owner, draft("cli_a1b2c3d4e5f6"));
        UUID hr = assistant("Nhân sự", false, true);
        UUID finance = assistant("Tài chính", false, true);
        UUID readOnly = assistant("Pháp chế", false, false);
        UUID builtin = assistant("MemoryOS", true, true);

        var bound = channels.bindPersona(owner, bot.id(), hr);
        assertEquals(Optional.of(hr), bindings.bound(TENANT, bot.id()));
        assertEquals(bot.revision(), channels.get(owner, bot.id()).revision(), "binding leaves the bot's revision as it was");
        assertEquals(List.of(bound), channels.boundTo(owner, hr));
        assertEquals(hr, channels.listWithState(owner).getFirst().assistantId());
        assertEquals("Nhân sự", channels.listWithState(owner).getFirst().assistant());

        // A bot bound elsewhere moves in one step.
        channels.bindPersona(owner, bot.id(), finance);
        assertEquals(Optional.of(finance), bindings.bound(TENANT, bot.id()));
        assertEquals(List.of(), channels.boundTo(owner, hr));

        assertEquals("CHANNEL_ASSISTANT_NOT_EDITABLE", code(() -> channels.bindPersona(owner, bot.id(), readOnly)));
        assertEquals("CHANNEL_NOT_FOUND", code(() -> channels.bindPersona(owner, UUID.randomUUID(), hr)));
        // Without the capability to manage bots nothing is bound, whatever the assistant.
        assertThrows(BusinessException.class, () -> channels.bindPersona(member, bot.id(), hr));
        when(personas.get(any(), eq(hr))).thenThrow(ChatException.unavailable());
        assertEquals("CHAT_UNAVAILABLE", code(() -> channels.bindPersona(owner, bot.id(), hr)));

        // The built-in assistant is no binding.
        channels.bindPersona(owner, bot.id(), builtin);
        assertEquals(Optional.empty(), bindings.bound(TENANT, bot.id()));
        assertNull(channels.listWithState(owner).getFirst().assistant());
        assertEquals(List.of("channel.create", "channel.update", "channel.update", "channel.update"), actions());
    }

    @Test
    void movingABotAlsoTakesEditRightsOnTheAssistantItLeaves() {
        var bot = channels.create(owner, draft("cli_a1b2c3d4e5f6"));
        UUID legal = assistant("Pháp chế", false, false);
        UUID hr = assistant("Nhân sự", false, true);
        bindings.bind(TENANT, bot.id(), legal);

        assertEquals("CHANNEL_ASSISTANT_NOT_EDITABLE", code(() -> channels.bindPersona(owner, bot.id(), hr)));
        assertEquals("CHANNEL_ASSISTANT_NOT_EDITABLE", code(() -> channels.bindPersona(owner, bot.id(), null)));
        assertEquals(Optional.of(legal), bindings.bound(TENANT, bot.id()), "the bot stays where it was");
    }

    @Test
    void removingABotOrItsAssistantRowClearsTheBinding() {
        var bot = channels.create(owner, draft("cli_a1b2c3d4e5f6"));
        var other = channels.create(owner, draft("cli_b7c3e9f1a2d4c6e8"));
        UUID hr = assistant("Nhân sự", false, true);
        channels.bindPersona(owner, bot.id(), hr);
        channels.bindPersona(owner, other.id(), hr);
        channels.delete(owner, other.id(), channels.get(owner, other.id()).revision());
        assertEquals(Optional.empty(), bindings.bound(TENANT, other.id()));
        jdbc.sql("DELETE FROM persona WHERE id = :id").param("id", hr).update();
        assertEquals(Optional.empty(), bindings.bound(TENANT, bot.id()));
    }

    /** An assistant as Chat reports it to the caller; its row exists so the binding's key holds. */
    private UUID assistant(String name, boolean builtin, boolean editable) {
        UUID id = UUID.randomUUID();
        if (!builtin) jdbc.sql("INSERT INTO persona(id, tenant_id, name, instructions) VALUES (:id, :tenant, :name, '')")
                .param("id", id).param("tenant", TENANT.value()).param("name", name).update();
        var view = mock(ChatPersonaService.PersonaView.class);
        when(view.id()).thenReturn(id);
        when(view.builtin()).thenReturn(builtin);
        when(view.permissions()).thenReturn(new PersonaPermissions(editable, editable, false, editable, false, false, false));
        when(personas.get(any(), eq(id))).thenReturn(view);
        doAnswer(call -> {
            var names = new HashMap<UUID, String>();
            for (UUID candidate : call.<Collection<UUID>>getArgument(1)) {
                jdbc.sql("SELECT name FROM persona WHERE id = :id AND builtin_key IS NULL").param("id", candidate)
                        .query(String.class).optional().ifPresent(found -> names.put(candidate, found));
            }
            return names;
        }).when(personas).names(any(), any());
        return id;
    }

    private List<String> actions() {
        return jdbc.sql("SELECT action FROM audit_event WHERE action LIKE 'channel.%' ORDER BY occurred_at, id")
                .query(String.class).list();
    }

    private static String code(Runnable call) {
        return assertThrows(BusinessException.class, call::run).code();
    }

    /** A platform that accepts or refuses every app, and records the secrets it was shown. */
    private static final class Platform implements ChannelAdapter {
        final List<String> secrets = new ArrayList<>();
        boolean refusing;
        @Nullable String answeredAppId;

        @Override public ChannelProvider provider() { return ChannelProvider.LARK; }

        @Override public ChannelFeatures features() { return new ChannelFeatures(true, true, 150_000); }

        @Override
        public ChannelVerification verify(ChannelCredentials credentials) {
            secrets.add(credentials.secret());
            if (refusing) throw ChannelException.rejected();
            String appId = answeredAppId != null ? answeredAppId : Objects.requireNonNull(credentials.appId());
            return new ChannelVerification(appId, "MemoryOS Bot", "ou_bot", Set.of("contact:user.email:readonly"));
        }

        /** A region is kept as given; a setting it does not have is refused, as a provider's adapter refuses one. */
        @Override
        public Map<String, String> settings(Map<String, String> requested) {
            if (!Set.of(ChannelRegion.SETTING).containsAll(requested.keySet())) throw ChannelException.invalid("unknown setting");
            return Map.copyOf(requested);
        }

        @Override
        public ChannelTransport open(ChannelConnection connection, String secret, Consumer<ChannelMessage> receiver) {
            throw new UnsupportedOperationException();
        }

        @Override public ChannelReply reply(ChannelConnection connection, ChannelMessage message, String waiting) { throw new UnsupportedOperationException(); }

        @Override public void notice(ChannelConnection connection, ChannelMessage message, String text) {}

        @Override public void acknowledge(ChannelConnection connection, ChannelMessage message) {}

        @Override public ChannelSender sender(ChannelConnection connection, String senderId) { return ChannelSender.UNKNOWN; }

        @Override public boolean externalChat(ChannelConnection connection, String chatId) { return false; }
    }
}
