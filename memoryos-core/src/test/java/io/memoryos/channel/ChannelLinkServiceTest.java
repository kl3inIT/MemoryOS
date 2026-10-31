package io.memoryos.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.memoryos.channel.ChannelLinkService.Redemption.ALREADY_LINKED;
import static io.memoryos.channel.ChannelLinkService.Redemption.LINKED;
import static io.memoryos.channel.ChannelLinkService.Redemption.NOT_LIVE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.channel.persistence.JdbcChannelConnectionRepository;
import io.memoryos.channel.persistence.JdbcChannelLinkRepository;
import io.memoryos.iam.Authority;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Codes and links at the PostgreSQL boundary (MEM-263). */
@Testcontainers
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChannelLinkServiceTest {
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TenantId tenant;
    private UUID connection;
    private ActorId ha;
    private ActorId quan;
    private ChannelLinkService links;
    private final IamAuthorization authorization = mock(IamAuthorization.class);

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,'Links','ACTIVE','test')")
                .param("id", tenant.value()).param("slug", tenant.value().toString()).update();
        var bot = member();
        ha = member();
        quan = member();
        connection = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO channel_connections(tenant_id, id, provider, name, settings, app_id, secret_ciphertext, secret_nonce,
                                                secret_key_version, notice_language, enabled, service_account_id)
                VALUES (:tenant, :id, 'LARK', 'Zalo bot', '{"region": "LARK"}', 'cli_a1b2c3d4e5f6', :secret, :nonce, 'test-1', 'vi', TRUE, :account)
                """).param("tenant", tenant.value()).param("id", connection).param("secret", new byte[32])
                .param("nonce", new byte[12]).param("account", bot.value()).update();
        when(authorization.require(any(), eq(IamCapability.CHAT_WRITE), eq(false))).thenReturn(new IamAccess(tenant, Authority.GLOBAL));
        links = service(false);
    }

    @AfterEach
    void close() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void aCodeIsEightUnambiguousCharactersLiveTenMinutesAndKeptOnlyAsAHash() {
        var made = links.code(ha, connection);

        assertTrue(made.code().matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}"), made.code());
        assertFalse(Duration.between(Instant.now(), made.expiresAt()).compareTo(Duration.ofMinutes(10)) > 0);
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM channel_link_codes").query(Long.class).single());
        assertEquals(32, jdbc.sql("SELECT octet_length(code_hash) FROM channel_link_codes").query(Integer.class).single(),
                "a SHA-256 hash is kept, not the code");
    }

    @Test
    void aNewCodeReplacesTheMembersOlderOneAndACodeIsUsedOnce() {
        String older = links.code(ha, connection).code();
        String newer = links.code(ha, connection).code();
        assertNotEquals(older, newer);

        assertEquals(NOT_LIVE, links.redeem(connection, "zu_ha", older), "the older code no longer works");
        assertEquals(LINKED, links.redeem(connection, "zu_ha", newer));
        assertEquals(NOT_LIVE, links.redeem(connection, "zu_other", newer), "a code links once");
        assertEquals(Optional.of(ha), links.linked(connection, "zu_ha"));
        assertEquals(Optional.empty(), links.linked(connection, "zu_other"));
    }

    @Test
    void aCodeMadeForOneBotDoesNotLinkOnAnother() {
        var other = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO channel_connections(tenant_id, id, provider, name, settings, app_id, secret_ciphertext, secret_nonce,
                                                secret_key_version, notice_language, enabled, service_account_id)
                SELECT tenant_id, :id, provider, 'Other bot', settings, 'cli_f6e5d4c3b2a1', secret_ciphertext, secret_nonce,
                       secret_key_version, notice_language, enabled, :account
                FROM channel_connections WHERE id = :connection
                """).param("id", other).param("connection", connection).param("account", member().value()).update();
        String code = links.code(ha, connection).code();

        assertEquals(NOT_LIVE, links.redeem(other, "zu_ha", code));
        assertEquals(Optional.empty(), links.linked(other, "zu_ha"));
        assertEquals(LINKED, links.redeem(connection, "zu_ha", code), "the code still works on its own bot");
    }

    @Test
    void anExpiredCodeDoesNotLink() {
        var made = links.code(ha, connection);
        jdbc.sql("UPDATE channel_link_codes SET expires_at = now() - INTERVAL '1 second'").update();

        assertEquals(NOT_LIVE, links.redeem(connection, "zu_ha", made.code()));
        assertEquals(Optional.empty(), links.linked(connection, "zu_ha"));
    }

    @Test
    void aMemberRelinksTheirOwnAccountButNoCodeTakesAnAccountLinkedToAnotherMember() {
        assertEquals(LINKED, links.redeem(connection, "zu_a", links.code(ha, connection).code()));
        // The member links another account of theirs: the first one is let go.
        assertEquals(LINKED, links.redeem(connection, "zu_b", links.code(ha, connection).code()));
        assertEquals(Optional.empty(), links.linked(connection, "zu_a"));
        assertEquals(Optional.of(ha), links.linked(connection, "zu_b"));

        // Another member's code sent from that account does not move it, and stays live for its own member.
        String quans = links.code(quan, connection).code();
        assertEquals(ALREADY_LINKED, links.redeem(connection, "zu_b", quans));
        assertEquals(Optional.of(ha), links.linked(connection, "zu_b"));
        assertEquals(LINKED, links.redeem(connection, "zu_q", quans));
        assertEquals(Optional.of(quan), links.linked(connection, "zu_q"));

        // Once Ha unlinks, the account can be linked to Quan.
        links.unlink(ha, connection);
        assertEquals(LINKED, links.redeem(connection, "zu_b", links.code(quan, connection).code()));
        assertEquals(Optional.of(quan), links.linked(connection, "zu_b"));
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM channel_account_links").query(Long.class).single());
    }

    @Test
    void anAccountLinkedToAMemberNoLongerActiveCanBeLinkedAgain() {
        assertEquals(LINKED, links.redeem(connection, "zu_a", links.code(ha, connection).code()));
        jdbc.sql("UPDATE tenant_memberships SET status = 'INACTIVE' WHERE actor_id = :actor").param("actor", ha.value()).update();

        assertEquals(LINKED, links.redeem(connection, "zu_a", links.code(quan, connection).code()));
        assertEquals(Optional.of(quan), links.linked(connection, "zu_a"));
    }

    @Test
    void theDatabaseNeverMovesALinkToAnotherMember() {
        var repository = new JdbcChannelLinkRepository(jdbc);
        assertTrue(repository.link(tenant, connection, "zu_a", ha, Instant.now()));
        assertFalse(repository.link(tenant, connection, "zu_a", quan, Instant.now()));
        assertTrue(repository.link(tenant, connection, "zu_a", ha, Instant.now()), "relinking one's own account is kept");
        assertEquals(Optional.of(ha), links.linked(connection, "zu_a"));
    }

    @Test
    void theMemberSeesTheBotsThatTakeALinkAndUnlinks() {
        var before = links.links(ha);
        assertEquals(1, before.size());
        assertEquals("Zalo bot", before.getFirst().botName());
        assertNull(before.getFirst().linkedAt());

        links.redeem(connection, "zu_ha", links.code(ha, connection).code());
        assertNotNull(links.links(ha).getFirst().linkedAt());
        assertNull(links.links(quan).getFirst().linkedAt(), "another member's link is not theirs");

        links.code(ha, connection);
        links.unlink(ha, connection);
        assertNull(links.links(ha).getFirst().linkedAt());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM channel_link_codes").query(Long.class).single(),
                "unlinking drops the code the member had made as well");
        // Unlinking again changes nothing.
        links.unlink(ha, connection);
    }

    @Test
    void aBotOfAPlatformWithOrganisationsOrOfNoBotTakesNoLink() {
        var lark = service(true);
        assertTrue(lark.links(ha).isEmpty());
        assertEquals("CHANNEL_NOT_FOUND", assertThrows(ChannelException.class, () -> lark.code(ha, connection)).code());
        assertEquals("CHANNEL_NOT_FOUND", assertThrows(ChannelException.class, () -> links.code(ha, UUID.randomUUID())).code());
    }

    @Test
    void codesAndLinksGoWithTheMembershipAndWithTheBot() {
        links.redeem(connection, "zu_ha", links.code(ha, connection).code());
        links.code(quan, connection);
        jdbc.sql("DELETE FROM tenant_memberships WHERE actor_id = :actor").param("actor", ha.value()).update();
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM channel_account_links").query(Long.class).single());

        jdbc.sql("DELETE FROM channel_connections WHERE id = :id").param("id", connection).update();
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM channel_link_codes").query(Long.class).single());
    }

    @Test
    void linkingAndUnlinkingAreAuditedWithTheMemberAndTheBotOnly() {
        String code = links.code(ha, connection).code();
        assertEquals(LINKED, links.redeem(connection, "zu_secret_id", code));
        links.unlink(ha, connection);
        // Nothing was linked: nothing to record.
        links.unlink(quan, connection);

        var events = jdbc.sql("""
                        SELECT action, event_class, actor_id, resource_type, resource_id, resource_label, details::text AS details
                        FROM audit_event WHERE action LIKE 'channel.account_%' ORDER BY occurred_at, id
                        """)
                .query((row, ignored) -> List.of(row.getString("action"), row.getString("event_class"),
                        row.getString("actor_id"), row.getString("resource_type"), row.getString("resource_id"),
                        row.getString("resource_label"), String.valueOf(row.getString("details"))))
                .list();
        assertEquals(2, events.size(), events.toString());
        assertEquals(List.of("channel.account_link", "ACCOUNT_CHANGE", ha.value().toString(), "CHANNEL",
                connection.toString(), "Zalo bot"), events.get(0).subList(0, 6));
        assertEquals(List.of("channel.account_unlink", "ACCOUNT_CHANGE", ha.value().toString(), "CHANNEL",
                connection.toString(), "Zalo bot"), events.get(1).subList(0, 6));
        String stored = jdbc.sql("SELECT string_agg(row_to_json(e)::text, ' ') FROM audit_event e WHERE action LIKE 'channel.account_%'")
                .query(String.class).single();
        assertFalse(stored.contains("zu_secret_id"), "no platform account id is recorded");
        assertFalse(stored.contains(code), "no code is recorded");
    }

    @Test
    void aMessageIsReadAsACodeOnlyWhenItIsOneCodeAlone() {
        assertEquals(Optional.of("ABCD2345"), ChannelLinkService.codeIn("  abcd-2345 "));
        assertEquals(Optional.empty(), ChannelLinkService.codeIn("ABCD 2345"), "two words are not a code");
        assertEquals(Optional.empty(), ChannelLinkService.codeIn("ABCDOI01"), "0, O, 1 and I are never in a code");
        assertEquals(Optional.empty(), ChannelLinkService.codeIn("ABCD23456"));
    }

    private ChannelLinkService service(boolean organisations) {
        var adapters = new ChannelAdapters(List.of(new Platform(new ChannelFeatures(organisations, false, 2_000, false))));
        var transactions = new DataSourceTransactionManager(dataSource);
        return new ChannelLinkService(authorization, new JdbcChannelConnectionRepository(jdbc), new JdbcChannelLinkRepository(jdbc),
                adapters, TestDatabase.audit(jdbc, transactions), transactions);
    }

    private ActorId member() {
        var actor = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant.value()).param("actor", actor.value()).update();
        return actor;
    }

    /** A platform of which only the features matter here. */
    private record Platform(ChannelFeatures features) implements ChannelAdapter {
        @Override public ChannelProvider provider() { return ChannelProvider.LARK; }

        @Override
        public ChannelTransport open(ChannelConnection connection, String secret, ChannelReceiver receiver) {
            throw new UnsupportedOperationException();
        }

        @Override public ChannelVerification verify(ChannelCredentials credentials) { throw new UnsupportedOperationException(); }

        @Override public void acknowledge(ChannelConnection connection, ChannelMessage message) {}

        @Override
        public ChannelReply reply(ChannelConnection connection, ChannelMessage message, String waiting) {
            throw new UnsupportedOperationException();
        }

        @Override public void notice(ChannelConnection connection, ChannelMessage message, String text) {}

        @Override public ChannelSender sender(ChannelConnection connection, String senderId) { return new ChannelSender(null); }

        @Override public ChannelChat chat(ChannelConnection connection, String chatId) { return ChannelChat.UNNAMED; }

        @Override public Map<String, String> settings(Map<String, String> requested) { return requested; }
    }
}
