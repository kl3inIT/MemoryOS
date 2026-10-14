package io.memoryos.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.channel.persistence.JdbcChannelConnectionRepository;
import io.memoryos.channel.persistence.JdbcChannelInboundRepository;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSurface;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChannelInboundServiceTest {
    private static final String ORGANISATION = "org-tasco";
    private static final String DIRECT_CHAT = "oc_direct";
    private static final String GROUP_CHAT = "oc_group";

    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private JdbcChatRepository chats;
    private JdbcChannelInboundRepository inbound;
    private final ChannelTurns turns = mock(ChannelTurns.class);
    private final FakeAdapter adapter = new FakeAdapter();
    private ChannelInboundService service;
    private TenantId tenant;
    private UUID persona;
    private UUID connection;
    private ActorId bot;
    private ActorId ha;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        chats = new JdbcChatRepository(jdbc);
        inbound = new JdbcChannelInboundRepository(jdbc);
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,'Channel','ACTIVE','test')")
                .param("id", tenant.value()).param("slug", tenant.value().toString()).update();
        bot = member();
        ha = member();
        persona = UUID.randomUUID();
        jdbc.sql("INSERT INTO persona(id,tenant_id,name,instructions,builtin_key) VALUES(:id,:tenant,'Default','','default')")
                .param("id", persona).param("tenant", tenant.value()).update();
        connection = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO channel_connections(tenant_id, id, provider, name, region, app_id, secret_ciphertext, secret_nonce,
                                                secret_key_version, notice_language, enabled, service_account_id, organisation_key)
                VALUES (:tenant, :id, 'LARK', 'Bot', 'LARK', 'cli_a1b2c3d4e5f6', :secret, :nonce, 'test-1', 'en', TRUE, :account, :organisation)
                """).param("tenant", tenant.value()).param("id", connection).param("secret", new byte[32])
                .param("nonce", new byte[12]).param("account", bot.value()).param("organisation", ORGANISATION).update();
        // The turn itself is Chat's and is covered there; here it answers at once, in a session that really exists.
        when(turns.ask(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            ActorId actor = call.getArgument(0);
            UUID session = call.getArgument(1);
            if (session == null) session = newSession(actor, call.getArgument(2));
            call.<Consumer<String>>getArgument(5).accept("An");
            return new ChannelTurns.Answer(session, UUID.randomUUID(), ChatMessage.Status.COMPLETED, "Answer", List.of(), null);
        });
        when(turns.owns(any(), any())).thenAnswer(call -> jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM chat_session WHERE id = :id AND owner_actor_id = :actor)")
                .param("id", call.<UUID>getArgument(1)).param("actor", call.<ActorId>getArgument(0).value())
                .query(Boolean.class).single());
        service = new ChannelInboundService(new JdbcChannelConnectionRepository(jdbc), inbound, new ChannelAdapters(List.of(adapter)),
                (ignored, email) -> "ha@tasco.vn".equals(email) ? Optional.of(ha) : Optional.empty(), turns,
                new ChannelProperties(Duration.ofHours(1), Duration.ofHours(1), Duration.ofDays(90)));
    }

    @AfterEach
    void close() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void aDirectMessageRunsAsItsSenderAndIsAnsweredOnce() throws Exception {
        adapter.emails.put("ou_ha", "ha@tasco.vn");
        var message = direct("m1", "ou_ha", "How many leave days?", null);
        service.receive(message);
        // Lark pushes a message again when its handler was slow; it is the same message.
        service.receive(message);

        verify(turns, times(1)).ask(eq(ha), any(), eq("How many leave days?"), any(), eq("How many leave days?"), any());
        assertEquals(List.of("append:An", "finish:Answer|footer=null"), adapter.replies);
        assertEquals("ANSWERED", outcome("m1"));
        assertEquals(ha.value(), owner(session("m1")));
    }

    @Test
    void aSenderWithoutAnAccountIsAnsweredAsTheBotAndRememberedForAWhile() throws Exception {
        service.receive(direct("m1", "ou_guest", "Question", null));
        service.receive(direct("m2", "ou_guest", "Follow-up", null));

        verify(turns, times(2)).ask(eq(bot), any(), any(), any(), any(), any());
        assertEquals(1, adapter.emailLookups, "the provider is asked once while the answer is remembered");
        assertTrue(adapter.replies.get(1).contains("Sign in to MemoryOS"));
        assertEquals(session("m1"), session("m2"), "a second direct message within the hour continues the first");
    }

    @Test
    void aGroupIsAnsweredOnlyWhenTheBotIsNamedAndAsTheBot() throws Exception {
        adapter.emails.put("ou_ha", "ha@tasco.vn");
        service.receive(group("g0", "ou_ha", "just chatting", false, null));
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM channel_messages").query(Long.class).single());

        service.receive(group("g1", "ou_ha", "What is the policy?", true, null));
        verify(turns).ask(eq(bot), any(), any(), any(), any(), any());
        assertTrue(adapter.replies.getLast().contains("Reply to this message to continue"));
        // A new message in the group starts a conversation of its own.
        service.receive(group("g2", "ou_ha", "Another question", true, null));
        assertNotEquals(session("g1"), session("g2"));
    }

    @Test
    void aReplyContinuesItsConversationHoweverOldAndWhoeverReplies() throws Exception {
        service.receive(group("g1", "ou_ha", "What is the policy?", true, null));
        String answer = adapter.replyIds.getLast();
        jdbc.sql("UPDATE channel_messages SET received_at = received_at - INTERVAL '30 days'").update();

        // Another member replies to the bot's answer, and a third to the question itself.
        service.receive(group("g2", "ou_quan", "And for interns?", true, answer));
        service.receive(group("g3", "ou_mai", "And contractors?", true, "g1"));

        assertEquals(session("g1"), session("g2"));
        assertEquals(session("g1"), session("g3"));
    }

    @Test
    void aDirectConversationEndsAfterTheIdleWindowOrOnTheNewCommand() throws Exception {
        adapter.emails.put("ou_ha", "ha@tasco.vn");
        service.receive(direct("m1", "ou_ha", "First", null));
        service.receive(direct("m2", "ou_ha", "Second", null));
        assertEquals(session("m1"), session("m2"));

        jdbc.sql("UPDATE channel_conversations SET last_message_at = last_message_at - INTERVAL '2 hours'").update();
        service.receive(direct("m3", "ou_ha", "Next day", null));
        assertNotEquals(session("m2"), session("m3"));

        service.receive(direct("m4", "ou_ha", " /new ", null));
        assertEquals("A new conversation has started.", adapter.notices.getLast());
        service.receive(direct("m5", "ou_ha", "Fresh", null));
        assertNotEquals(session("m3"), session("m5"));
        verify(turns, times(4)).ask(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aConversationIsContinuedOnlyByWhoeverItBelongsTo() throws Exception {
        // Asked without an account, so the conversation is the bot's.
        service.receive(direct("m1", "ou_ha", "Before the account", null));
        // The account is found on the next look: the same person now asks as themself, in a conversation of their own.
        adapter.emails.put("ou_ha", "ha@tasco.vn");
        jdbc.sql("UPDATE channel_identities SET resolved_at = resolved_at - INTERVAL '2 hours'").update();
        service.receive(direct("m2", "ou_ha", "After the account", null));

        assertEquals(bot.value(), owner(session("m1")));
        assertEquals(ha.value(), owner(session("m2")));
    }

    @Test
    void outsidersOtherMessageTypesAndExternalGroupsAreRefusedWithANotice() throws Exception {
        service.receive(new ChannelMessage(connection, "x1", DIRECT_CHAT, ChannelMessage.Kind.DIRECT, "ou_out", null,
                "another-org", ORGANISATION, true, ChannelMessage.Type.TEXT, "Hello", null));
        assertEquals("This assistant serves members of the organisation only.", adapter.notices.getLast());
        assertEquals("REFUSED:OUTSIDER", outcome("x1") + ":" + reason("x1"));

        service.receive(new ChannelMessage(connection, "x2", DIRECT_CHAT, ChannelMessage.Kind.DIRECT, "ou_ha", null,
                ORGANISATION, ORGANISATION, true, ChannelMessage.Type.OTHER, "", null));
        assertEquals("REFUSED:NOT_TEXT", outcome("x2") + ":" + reason("x2"));

        adapter.externalChats.add(GROUP_CHAT);
        service.receive(group("x3", "ou_ha", "Question", true, null));
        assertEquals("REFUSED:EXTERNAL_GROUP", outcome("x3") + ":" + reason("x3"));
        verify(turns, never()).ask(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aBotOfAnotherOrganisationOrThatIsSwitchedOffAnswersNobody() throws Exception {
        // The event names another organisation than the one recorded for the bot.
        service.receive(new ChannelMessage(connection, "m1", DIRECT_CHAT, ChannelMessage.Kind.DIRECT, "ou_ha", null,
                "org-other", "org-other", true, ChannelMessage.Type.TEXT, "Question", null));
        assertEquals("REFUSED:OUTSIDER", outcome("m1") + ":" + reason("m1"));

        jdbc.sql("UPDATE channel_connections SET enabled = FALSE, organisation_key = :key").param("key", ORGANISATION).update();
        service.receive(direct("m2", "ou_ha", "Question", null));
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM channel_messages").query(Long.class).single());
        verify(turns, never()).ask(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aTurnThatIsRefusedOrFailsBecomesANoticeAndARecordedReason() throws Exception {
        reset(turns);
        when(turns.ask(any(), any(), any(), any(), any(), any())).thenThrow(ChatException.busy());
        service.receive(direct("m1", "ou_ha", "Question", null));
        assertEquals("fail:The assistant is busy. Ask again in a moment.", adapter.replies.getLast());
        assertEquals("FAILED:CHAT_CAPACITY_EXCEEDED", outcome("m1") + ":" + reason("m1"));

        UUID failed = newSession(bot, "Failed");
        reset(turns);
        when(turns.ask(any(), any(), any(), any(), any(), any())).thenReturn(new ChannelTurns.Answer(failed, UUID.randomUUID(),
                ChatMessage.Status.FAILED, "", List.of(), "CHAT_PROVIDER_UNAVAILABLE"));
        service.receive(direct("m2", "ou_ha", "Question", null));
        assertEquals("fail:The assistant could not answer this. Try again.", adapter.replies.getLast());
        assertEquals("FAILED:CHAT_PROVIDER_UNAVAILABLE", outcome("m2") + ":" + reason("m2"));
    }

    @Test
    void oldMessageIdsAreSweptAndARecentOneIsKept() throws Exception {
        service.receive(direct("m1", "ou_ha", "Old", null));
        service.receive(direct("m2", "ou_ha", "Recent", null));
        jdbc.sql("UPDATE channel_messages SET received_at = received_at - INTERVAL '91 days' WHERE external_message_id = 'm1'").update();

        var retention = new ChannelMessageRetention(inbound, new ChannelProperties(Duration.ofHours(1), Duration.ofHours(1), Duration.ofDays(90)));
        assertEquals(1, retention.sweep());
        assertEquals(List.of("m2"), jdbc.sql("SELECT external_message_id FROM channel_messages").query(String.class).list());
        assertNull(inbound.sessionOf(connection, "m1").orElse(null));
    }

    private ChannelMessage direct(String id, String sender, String text, @Nullable String repliesTo) {
        return new ChannelMessage(connection, id, DIRECT_CHAT, ChannelMessage.Kind.DIRECT, sender, null, ORGANISATION, ORGANISATION, true,
                ChannelMessage.Type.TEXT, text, repliesTo);
    }

    private ChannelMessage group(String id, String sender, String text, boolean addressed, @Nullable String repliesTo) {
        return new ChannelMessage(connection, id, GROUP_CHAT, ChannelMessage.Kind.GROUP, sender, null, ORGANISATION, ORGANISATION, addressed,
                ChannelMessage.Type.TEXT, text, repliesTo);
    }

    /** A session and its root message are written together: the one refers to the other until the transaction ends. */
    private UUID newSession(ActorId actor, String title) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(_ -> chats.create(tenant, actor, persona, title, false, ChatSurface.CHANNEL).id());
    }

    private ActorId member() {
        var actor = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant.value()).param("actor", actor.value()).update();
        return actor;
    }

    private String outcome(String message) {
        return jdbc.sql("SELECT outcome FROM channel_messages WHERE external_message_id = :id").param("id", message)
                .query(String.class).single();
    }

    private @Nullable String reason(String message) {
        return jdbc.sql("SELECT reason FROM channel_messages WHERE external_message_id = :id").param("id", message)
                .query(String.class).optional().orElse(null);
    }

    private UUID session(String message) {
        return jdbc.sql("SELECT session_id FROM channel_messages WHERE external_message_id = :id").param("id", message)
                .query(UUID.class).single();
    }

    private UUID owner(UUID session) {
        return jdbc.sql("SELECT owner_actor_id FROM chat_session WHERE id = :id").param("id", session).query(UUID.class).single();
    }

    /** A platform that records what the capability asks of it. */
    private static final class FakeAdapter implements ChannelAdapter {
        final Map<String, String> emails = new HashMap<>();
        final Set<String> externalChats = new HashSet<>();
        final List<String> replies = new ArrayList<>();
        final List<String> replyIds = new ArrayList<>();
        final List<String> notices = new ArrayList<>();
        int emailLookups;

        @Override public ChannelProvider provider() { return ChannelProvider.LARK; }

        @Override
        public ChannelTransport open(ChannelConnection connection, String secret, Consumer<ChannelMessage> receiver) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelReply reply(ChannelConnection connection, ChannelMessage message) {
            return new ChannelReply() {
                @Override public void append(String text) { replies.add("append:" + text); }

                @Override
                public String finish(String text, List<Source> sources, @Nullable String footer) {
                    replies.add("finish:" + text + "|footer=" + footer);
                    String id = "reply-to-" + message.messageId();
                    replyIds.add(id);
                    return id;
                }

                @Override public void fail(String notice) { replies.add("fail:" + notice); }
            };
        }

        @Override
        public void notice(ChannelConnection connection, ChannelMessage message, String text) {
            notices.add(text);
        }

        @Override
        public Optional<String> senderEmail(ChannelConnection connection, String senderId) {
            emailLookups++;
            return Optional.ofNullable(emails.get(senderId));
        }

        @Override
        public boolean externalChat(ChannelConnection connection, String chatId) {
            return externalChats.contains(chatId);
        }

        @Override public ChannelVerification verify(ChannelRegion region, String appId, String secret) { throw new UnsupportedOperationException(); }
    }
}
