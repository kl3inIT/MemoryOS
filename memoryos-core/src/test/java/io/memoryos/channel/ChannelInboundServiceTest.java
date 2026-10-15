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
import io.memoryos.channel.persistence.JdbcChannelLinkRepository;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatSurface;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.iam.Authority;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
    private static final ChannelMessage.Organisations MEMBER = new ChannelMessage.Organisations(ORGANISATION, ORGANISATION);

    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private JdbcChatRepository chats;
    private JdbcChannelInboundRepository inbound;
    private final ChannelTurns turns = mock(ChannelTurns.class);
    private final FakeAdapter adapter = new FakeAdapter();
    private ChannelInboundService service;
    private ChannelLinkService links;
    private TenantId tenant;
    private UUID persona;
    private UUID connection;
    private ActorId bot;
    private ActorId ha;
    private final List<String> spans = new CopyOnWriteArrayList<>();

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
                INSERT INTO channel_connections(tenant_id, id, provider, name, settings, app_id, secret_ciphertext, secret_nonce,
                                                secret_key_version, notice_language, enabled, service_account_id, organisation_key)
                VALUES (:tenant, :id, 'LARK', 'Bot', '{"region": "LARK"}', 'cli_a1b2c3d4e5f6', :secret, :nonce, 'test-1', 'en', TRUE, :account, :organisation)
                """).param("tenant", tenant.value()).param("id", connection).param("secret", new byte[32])
                .param("nonce", new byte[12]).param("account", bot.value()).param("organisation", ORGANISATION).update();
        // The turn itself is Chat's and is covered there; here it answers at once, in a session that really exists.
        when(turns.ask(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            ActorId actor = call.getArgument(0);
            UUID session = call.getArgument(1);
            if (session == null) session = newSession(actor, call.getArgument(2));
            call.<Supplier<Consumer<String>>>getArgument(7).get().accept("An");
            return new ChannelTurns.Answer(session, UUID.randomUUID(), ChatMessage.Status.COMPLETED, "Answer", List.of(), null);
        });
        when(turns.owns(any(), any())).thenAnswer(call -> jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM chat_session WHERE id = :id AND owner_actor_id = :actor)")
                .param("id", call.<UUID>getArgument(1)).param("actor", call.<ActorId>getArgument(0).value())
                .query(Boolean.class).single());
        var observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override public boolean supportsContext(Observation.Context context) { return true; }

            @Override
            public void onStop(Observation.Context context) {
                var step = context.getLowCardinalityKeyValue("step");
                var outcome = context.getLowCardinalityKeyValue("outcome");
                spans.add(context.getName() + ":" + (step != null ? step.getValue() : outcome == null ? "" : outcome.getValue()));
            }
        });
        var authorization = mock(IamAuthorization.class);
        when(authorization.require(any(), eq(IamCapability.CHAT_WRITE), eq(false))).thenReturn(new IamAccess(tenant, Authority.GLOBAL));
        var adapters = new ChannelAdapters(List.of(adapter));
        links = new ChannelLinkService(authorization, new JdbcChannelConnectionRepository(jdbc), new JdbcChannelLinkRepository(jdbc),
                adapters, new DataSourceTransactionManager(dataSource));
        service = new ChannelInboundService(new JdbcChannelConnectionRepository(jdbc), inbound, adapters,
                (ignored, email) -> "ha@tasco.vn".equals(email) ? Optional.of(ha) : Optional.empty(), links, turns,
                new ChannelProperties(Duration.ofHours(1), Duration.ofHours(1), Duration.ofDays(90), 10, Duration.ofSeconds(3),
                "https://memoryos.example/"), observations);
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

        verify(turns, times(1)).ask(eq(ha), any(), eq("How many leave days?"), any(), eq("How many leave days?"), any(), any(), any());
        // A sender's own direct answer links to its session on the web, where it continues.
        assertEquals(List.of("append:An", "finish:Answer|footer=[Continue in MemoryOS](https://memoryos.example/chat/"
                + session("m1") + ")"), adapter.replies);
        assertEquals("ANSWERED", outcome("m1"));
        assertEquals(ha.value(), owner(session("m1")));
        // Each step of the answer is a span inside the message's, so the time before a turn starts can be read.
        assertEquals(List.of("memoryos.channel.message.step:refusal", "memoryos.channel.message.step:sender",
                "memoryos.channel.message.step:conversation", "memoryos.channel.message.step:reply",
                "memoryos.channel.message.step:turn", "memoryos.channel.message.step:finish",
                "memoryos.channel.message:ANSWERED"), spans);
    }

    @Test
    void aSenderWithoutAnAccountIsAnsweredAsTheBotAndRememberedForAWhile() throws Exception {
        service.receive(direct("m1", "ou_guest", "Question", null));
        service.receive(direct("m2", "ou_guest", "Follow-up", null));

        verify(turns, times(2)).ask(eq(bot), any(), any(), any(), any(), any(), any(), any());
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
        verify(turns).ask(eq(bot), any(), any(), any(), any(), any(), any(), any());
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
        verify(turns, times(4)).ask(any(), any(), any(), any(), any(), any(), any(), any());
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
                new ChannelMessage.Organisations("another-org", ORGANISATION), true, ChannelMessage.Type.TEXT, "Hello", null));
        assertEquals("This assistant serves members of the organisation only.", adapter.notices.getLast());
        assertEquals("REFUSED:OUTSIDER", outcome("x1") + ":" + reason("x1"));

        service.receive(new ChannelMessage(connection, "x2", DIRECT_CHAT, ChannelMessage.Kind.DIRECT, "ou_ha", null,
                new ChannelMessage.Organisations(ORGANISATION, ORGANISATION), true, ChannelMessage.Type.OTHER, "", null));
        assertEquals("REFUSED:NOT_TEXT", outcome("x2") + ":" + reason("x2"));

        adapter.externalChats.add(GROUP_CHAT);
        service.receive(group("x3", "ou_ha", "Question", true, null));
        assertEquals("REFUSED:EXTERNAL_GROUP", outcome("x3") + ":" + reason("x3"));
        verify(turns, never()).ask(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aBotOfAnotherOrganisationOrThatIsSwitchedOffAnswersNobody() throws Exception {
        // The event names another organisation than the one recorded for the bot.
        service.receive(new ChannelMessage(connection, "m1", DIRECT_CHAT, ChannelMessage.Kind.DIRECT, "ou_ha", null,
                new ChannelMessage.Organisations("org-other", "org-other"), true, ChannelMessage.Type.TEXT, "Question", null));
        assertEquals("REFUSED:OUTSIDER", outcome("m1") + ":" + reason("m1"));

        jdbc.sql("UPDATE channel_connections SET enabled = FALSE, organisation_key = :key").param("key", ORGANISATION).update();
        service.receive(direct("m2", "ou_ha", "Question", null));
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM channel_messages").query(Long.class).single());
        verify(turns, never()).ask(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void onAPlatformWithoutOrganisationsAStrangerIsToldHowToLinkOnceAMinuteAndNotAnswered() throws Exception {
        adapter.features = new ChannelFeatures(false, false, 2_000);
        service.receive(zalo("z1", "zu_ha", "Question"));
        service.receive(zalo("z2", "zu_ha", "Hello?"));

        assertEquals("REFUSED:LINK_REQUIRED", outcome("z1") + ":" + reason("z1"));
        // Told once; the next message within the minute is refused without another notice.
        assertEquals("REFUSED:LINK_REQUIRED_QUIET", outcome("z2") + ":" + reason("z2"));
        assertEquals(1, adapter.notices.size());
        assertTrue(adapter.notices.getFirst().startsWith("To ask the assistant, link your MemoryOS account"));
        verify(turns, never()).ask(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aLiveCodeLinksItsSenderOnceWhoIsThenAnsweredAsThemself() throws Exception {
        adapter.features = new ChannelFeatures(false, false, 2_000);
        String code = links.code(ha, connection).code();
        service.receive(zalo("z1", "zu_ha", " " + code.substring(0, 4).toLowerCase() + "-" + code.substring(4) + " "));
        assertEquals("ANSWERED:LINKED", outcome("z1") + ":" + reason("z1"));
        assertEquals("Your MemoryOS account is linked. You can ask the assistant now.", adapter.notices.getLast());

        service.receive(zalo("z2", "zu_ha", "How many leave days?"));
        verify(turns).ask(eq(ha), any(), any(), any(), eq("How many leave days?"), any(), any(), any());
        assertEquals(ha.value(), owner(session("z2")));

        // The code is used up: another account sending it is told it is wrong.
        service.receive(zalo("z3", "zu_other", code));
        assertEquals("REFUSED:LINK_CODE_INVALID", outcome("z3") + ":" + reason("z3"));
        assertEquals("The linking code is wrong or has expired. Get a new one in Settings › Chat in MemoryOS.",
                adapter.notices.getLast());
    }

    @Test
    void anExpiredCodeOrOneSentInAGroupDoesNotLink() throws Exception {
        adapter.features = new ChannelFeatures(false, false, 2_000);
        String code = links.code(ha, connection).code();
        service.receive(new ChannelMessage(connection, "g1", GROUP_CHAT, ChannelMessage.Kind.GROUP, "zu_ha", null, null, true,
                ChannelMessage.Type.TEXT, code, null));
        assertEquals("REFUSED:LINK_REQUIRED", outcome("g1") + ":" + reason("g1"));

        jdbc.sql("UPDATE channel_link_codes SET expires_at = now() - INTERVAL '1 second'").update();
        service.receive(zalo("z1", "zu_ha", code));
        assertEquals("REFUSED:LINK_CODE_INVALID", outcome("z1") + ":" + reason("z1"));
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM channel_account_links").query(Long.class).single());
    }

    @Test
    void aSenderWithFiveWrongCodesInAnHourIsNotHeardEvenWithALiveOne() throws Exception {
        adapter.features = new ChannelFeatures(false, false, 2_000);
        for (int i = 1; i <= 5; i++) service.receive(zalo("w" + i, "zu_guess", "ABCDEFG" + (i + 1)));
        String code = links.code(ha, connection).code();
        service.receive(zalo("w6", "zu_guess", code));

        assertEquals("REFUSED:LINK_LOCKED", outcome("w6") + ":" + reason("w6"));
        assertEquals(5, adapter.notices.size(), "the locked sender is not answered");
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM channel_account_links").query(Long.class).single());
    }

    @Test
    void aLinkedSenderInAGroupIsAnsweredAsTheBotAndAStrangerIsToldHowToLink() throws Exception {
        adapter.features = new ChannelFeatures(false, false, 2_000);
        service.receive(zalo("z1", "zu_ha", links.code(ha, connection).code()));

        service.receive(new ChannelMessage(connection, "g1", GROUP_CHAT, ChannelMessage.Kind.GROUP, "zu_ha", "Hà", null, true,
                ChannelMessage.Type.TEXT, "Leave policy?", null));
        verify(turns).ask(eq(bot), any(), any(), any(), eq("[Hà]: Leave policy?"), any(), any(), any());

        service.receive(new ChannelMessage(connection, "g2", GROUP_CHAT, ChannelMessage.Kind.GROUP, "zu_guest", null, null, true,
                ChannelMessage.Type.TEXT, "Leave policy?", null));
        assertEquals("REFUSED:LINK_REQUIRED", outcome("g2") + ":" + reason("g2"));
        verify(turns, times(1)).ask(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aLinkIsLeftOutOnceItsMemberIsNoLongerActive() throws Exception {
        adapter.features = new ChannelFeatures(false, false, 2_000);
        service.receive(zalo("z1", "zu_ha", links.code(ha, connection).code()));
        jdbc.sql("UPDATE tenant_memberships SET status = 'INACTIVE' WHERE actor_id = :actor").param("actor", ha.value()).update();

        service.receive(zalo("z2", "zu_ha", "Question"));
        assertEquals("REFUSED:LINK_REQUIRED", outcome("z2") + ":" + reason("z2"));
        verify(turns, never()).ask(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aLinkedAccountIsNotMovedByAnotherCodeAndLearnsNothingOfIt() throws Exception {
        adapter.features = new ChannelFeatures(false, false, 2_000);
        var quan = member();
        service.receive(zalo("z0", "zu_ha", links.code(ha, connection).code()));
        String quans = links.code(quan, connection).code();

        // A live code of another member and a wrong one get the same answer, and neither is tried.
        service.receive(zalo("c1", "zu_ha", quans));
        service.receive(zalo("c2", "zu_ha", "ABCDEFG2"));
        assertEquals("REFUSED:LINK_ALREADY", outcome("c1") + ":" + reason("c1"));
        assertEquals("REFUSED:LINK_ALREADY", outcome("c2") + ":" + reason("c2"));
        assertEquals(adapter.notices.get(1), adapter.notices.get(2));
        assertTrue(adapter.notices.getLast().startsWith("This account is already linked to a MemoryOS account."));
        verify(turns, never()).ask(any(), any(), any(), any(), any(), any(), any(), any());

        // Ha's account is still Ha's, and Quan's code still links an account of Quan's.
        service.receive(zalo("q1", "zu_ha", "How many leave days?"));
        verify(turns).ask(eq(ha), any(), any(), any(), any(), any(), any(), any());
        service.receive(zalo("z1", "zu_quan", quans));
        assertEquals("ANSWERED:LINKED", outcome("z1") + ":" + reason("z1"));
    }

    @Test
    void everyCodeThatDoesNotLinkGetsTheSameNotice() throws Exception {
        adapter.features = new ChannelFeatures(false, false, 2_000);
        String used = links.code(ha, connection).code();
        service.receive(zalo("u0", "zu_ha", used));
        String expired = links.code(member(), connection).code();
        jdbc.sql("UPDATE channel_link_codes SET expires_at = now() - INTERVAL '1 second'").update();

        service.receive(zalo("c1", "zu_guess", used));
        service.receive(zalo("c2", "zu_guess", expired));
        service.receive(zalo("c3", "zu_guess", "ABCDEFG2"));
        assertEquals(List.of("REFUSED:LINK_CODE_INVALID", "REFUSED:LINK_CODE_INVALID", "REFUSED:LINK_CODE_INVALID"),
                List.of(outcome("c1") + ":" + reason("c1"), outcome("c2") + ":" + reason("c2"), outcome("c3") + ":" + reason("c3")));
        assertEquals(1, adapter.notices.subList(1, 4).stream().distinct().count());
    }

    @Test
    void codesStillBeingTriedCountTowardTheLock() throws Exception {
        adapter.features = new ChannelFeatures(false, false, 2_000);
        // Five attempts of the sender in flight: recorded as wrong until they link, not yet settled otherwise.
        for (int i = 1; i <= 5; i++) {
            jdbc.sql("""
                    INSERT INTO channel_messages(tenant_id, connection_id, external_message_id, external_chat_id, external_user_id,
                                                 outcome, reason)
                    VALUES (:tenant, :connection, :id, :chat, 'zu_guess', 'REFUSED', 'LINK_CODE_INVALID')
                    """).param("tenant", tenant.value()).param("connection", connection).param("id", "f" + i)
                    .param("chat", DIRECT_CHAT).update();
        }
        service.receive(zalo("z1", "zu_guess", links.code(ha, connection).code()));

        assertEquals("REFUSED:LINK_LOCKED", outcome("z1") + ":" + reason("z1"));
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM channel_account_links").query(Long.class).single());
    }

    @Test
    void aPlatformWithoutOrganisationsNeverAnswersFromAnEmailOrARememberedAccount() throws Exception {
        adapter.features = new ChannelFeatures(false, false, 2_000);
        adapter.emails.put("zu_ha", "ha@tasco.vn");
        inbound.remember(tenant, connection, "zu_ha", ha, Instant.now());

        service.receive(zalo("z1", "zu_ha", "Question"));
        assertEquals("REFUSED:LINK_REQUIRED", outcome("z1") + ":" + reason("z1"));
        verify(turns, never()).ask(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void anAccountRelinkedToAnotherMemberNeverContinuesTheFirstMembersConversation() throws Exception {
        adapter.features = new ChannelFeatures(false, false, 2_000);
        var quan = member();
        service.receive(zalo("z1", "zu_shared", links.code(ha, connection).code()));
        service.receive(zalo("z2", "zu_shared", "Ha's question"));
        String answer = adapter.replyIds.getLast();

        links.unlink(ha, connection);
        service.receive(zalo("z3", "zu_shared", links.code(quan, connection).code()));
        // A reply to Ha's answer, and a new direct message, both start Quan's own conversation.
        service.receive(new ChannelMessage(connection, "z4", DIRECT_CHAT, ChannelMessage.Kind.DIRECT, "zu_shared", null, null, true,
                ChannelMessage.Type.TEXT, "And then?", answer));
        service.receive(zalo("z5", "zu_shared", "Another"));

        assertEquals("ANSWERED:LINKED", outcome("z3") + ":" + reason("z3"));
        assertEquals(ha.value(), owner(session("z2")));
        assertEquals(quan.value(), owner(session("z4")));
        assertNotEquals(session("z2"), session("z4"));
        assertEquals(quan.value(), owner(session("z5")));
        assertNotEquals(session("z2"), session("z5"));
    }

    @Test
    void aReplyThatCannotBeRewrittenGetsTheWholeAnswerInPiecesAndNoPartialText() throws Exception {
        adapter.features = new ChannelFeatures(true, false, 4);
        service.receive(direct("m1", "ou_ha", "Question", null));

        assertEquals(1, adapter.replies.size());
        assertTrue(adapter.replies.getFirst().startsWith("finish:Answ|er|footer="), adapter.replies.getFirst());
    }

    @Test
    void aTurnThatIsRefusedOrFailsBecomesANoticeAndARecordedReason() throws Exception {
        reset(turns);
        when(turns.ask(any(), any(), any(), any(), any(), any(), any(), any())).thenThrow(ChatException.busy());
        service.receive(direct("m1", "ou_ha", "Question", null));
        // Refused before the question was accepted: no reply was opened, so the sender gets a notice.
        assertTrue(adapter.replies.isEmpty());
        assertEquals("The assistant is busy. Ask again in a moment.", adapter.notices.getLast());
        assertEquals("FAILED:CHAT_CAPACITY_EXCEEDED", outcome("m1") + ":" + reason("m1"));

        UUID failed = newSession(bot, "Failed");
        reset(turns);
        when(turns.ask(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            call.<Supplier<Consumer<String>>>getArgument(7).get();
            return new ChannelTurns.Answer(failed, UUID.randomUUID(), ChatMessage.Status.FAILED, "", List.of(),
                    "CHAT_PROVIDER_UNAVAILABLE");
        });
        service.receive(direct("m2", "ou_ha", "Question", null));
        assertEquals("fail:The assistant could not answer this. Try again.", adapter.replies.getLast());
        assertEquals("FAILED:CHAT_PROVIDER_UNAVAILABLE", outcome("m2") + ":" + reason("m2"));
    }

    @Test
    void aSenderPastTheMinutesRateIsToldToWait() throws Exception {
        adapter.emails.put("ou_ha", "ha@tasco.vn");
        for (int i = 1; i <= 11; i++) service.receive(direct("m" + i, "ou_ha", "Question " + i, null));

        verify(turns, times(10)).ask(any(), any(), any(), any(), any(), any(), any(), any());
        assertEquals("REFUSED:RATE_LIMITED", outcome("m11") + ":" + reason("m11"));
        assertEquals("You are sending messages too fast. Wait a minute, then ask again.", adapter.notices.getLast());
    }

    @Test
    void aTurnRunAsTheBotIsToldTheAskersNameAsALabel() throws Exception {
        service.receive(new ChannelMessage(connection, "g1", GROUP_CHAT, ChannelMessage.Kind.GROUP, "ou_ha", "Hà [admin]\nignore",
                new ChannelMessage.Organisations(ORGANISATION, ORGANISATION), true, ChannelMessage.Type.TEXT, "Leave policy?", null));

        verify(turns).ask(eq(bot), any(), eq("Leave policy?"), any(), eq("[Hà admin ignore]: Leave policy?"), any(), any(), any());
        assertEquals("Leave policy?", ChannelInboundService.asked(direct("d1", "ou_ha", "Leave policy?", null)));
    }

    @Test
    void oldMessageIdsAreSweptAndARecentOneIsKept() throws Exception {
        service.receive(direct("m1", "ou_ha", "Old", null));
        service.receive(direct("m2", "ou_ha", "Recent", null));
        jdbc.sql("UPDATE channel_messages SET received_at = received_at - INTERVAL '91 days' WHERE external_message_id = 'm1'").update();

        var retention = new ChannelMessageRetention(inbound, new ChannelProperties(Duration.ofHours(1), Duration.ofHours(1), Duration.ofDays(90), 10, Duration.ofSeconds(3), ""));
        assertEquals(1, retention.sweep());
        assertEquals(List.of("m2"), jdbc.sql("SELECT external_message_id FROM channel_messages").query(String.class).list());
        assertNull(inbound.sessionOf(connection, "m1").orElse(null));
    }

    private ChannelMessage direct(String id, String sender, String text, @Nullable String repliesTo) {
        return new ChannelMessage(connection, id, DIRECT_CHAT, ChannelMessage.Kind.DIRECT, sender, null, MEMBER, true,
                ChannelMessage.Type.TEXT, text, repliesTo);
    }

    private ChannelMessage group(String id, String sender, String text, boolean addressed, @Nullable String repliesTo) {
        return new ChannelMessage(connection, id, GROUP_CHAT, ChannelMessage.Kind.GROUP, sender, null, MEMBER, addressed,
                ChannelMessage.Type.TEXT, text, repliesTo);
    }

    private ChannelMessage zalo(String id, String sender, String text) {
        return new ChannelMessage(connection, id, DIRECT_CHAT, ChannelMessage.Kind.DIRECT, sender, null, null, true,
                ChannelMessage.Type.TEXT, text, null);
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
        final List<String> acknowledged = new ArrayList<>();
        int emailLookups;
        ChannelFeatures features = new ChannelFeatures(true, true, 150_000);

        @Override public ChannelProvider provider() { return ChannelProvider.LARK; }

        @Override public ChannelFeatures features() { return features; }

        @Override
        public ChannelTransport open(ChannelConnection connection, String secret, Consumer<ChannelMessage> receiver) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ChannelReply reply(ChannelConnection connection, ChannelMessage message, String waiting) {
            return new ChannelReply() {
                @Override public void append(String text) { replies.add("append:" + text); }

                @Override
                public String finish(List<String> pieces, List<Source> sources, @Nullable String footer) {
                    replies.add("finish:" + String.join("|", pieces) + "|footer=" + footer);
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
        public void acknowledge(ChannelConnection connection, ChannelMessage message) {
            acknowledged.add(message.messageId());
        }

        @Override
        public ChannelSender sender(ChannelConnection connection, String senderId) {
            emailLookups++;
            return new ChannelSender(emails.get(senderId));
        }

        @Override
        public boolean externalChat(ChannelConnection connection, String chatId) {
            return externalChats.contains(chatId);
        }

        @Override public ChannelVerification verify(ChannelCredentials credentials) { throw new UnsupportedOperationException(); }
    }
}
