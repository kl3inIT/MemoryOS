package io.memoryos.chat.history;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatSettingsService;
import io.memoryos.chat.history.persistence.JdbcChatHistoryRepository;
import io.memoryos.iam.group.Authority;
import io.memoryos.iam.group.IamAccess;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reading other people's conversations: who may, how much of them, and what is left out (MEM-125). */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ChatHistoryServiceTest {
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private org.springframework.transaction.support.TransactionTemplate tx;
    private ChatHistoryService history;
    private ChatSettingsService settings;
    private UUID tenant;
    private UUID asker;
    private UUID reader;
    private UUID persona;

    @BeforeEach void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.support.JdbcTransactionManager(dataSource));
        tenant = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,'History','ACTIVE',:reference)")
                .param("id", tenant).param("slug", tenant.toString()).param("reference", tenant.toString()).update();
        asker = person("Trần Thu Hà", "ha@tasco.vn");
        reader = person("Nguyễn Văn An", "an@tasco.vn");
        persona = UUID.randomUUID();
        jdbc.sql("INSERT INTO persona(id,tenant_id,name,instructions,model,builtin_key) VALUES(:id,:tenant,'Default','','gpt','default')")
                .param("id", persona).param("tenant", tenant).update();
        settings = mock(ChatSettingsService.class);
        when(settings.historyVisibility(any())).thenReturn(ChatHistoryVisibility.NORMAL);
        history = new ChatHistoryService(authorization(), settings, new JdbcChatHistoryRepository(jdbc),
                TestDatabase.noAudit());
    }

    @AfterEach void close() { if (dataSource != null) dataSource.close(); }

    @Test void aConversationIsListedWithWhoAskedWhatTheyAskedAndHowItWasRated() {
        var session = conversation("Nghỉ phép", "Tôi còn bao nhiêu ngày phép?", "Bạn còn 5 ngày.", false, false);
        rate(session, true);
        var page = history.page(new ActorId(reader), all(), null, 30);
        assertEquals(1, page.items().size());
        var entry = page.items().getFirst();
        assertEquals("Trần Thu Hà", entry.person());
        assertEquals("ha@tasco.vn", entry.email());
        assertEquals("Tôi còn bao nhiêu ngày phép?", entry.question());
        assertEquals("Bạn còn 5 ngày.", entry.answer());
        assertEquals(ChatHistoryFeedback.POSITIVE, entry.feedback());
        assertFalse(entry.deleted());
        assertEquals(1, page.totals().conversations());
        assertEquals(1, page.totals().positive());
    }

    @Test void aTemporaryConversationIsAbsentAndADeletedOneIsListedAndSaysSo() {
        conversation("Tạm", "Hỏi nhanh", "Trả lời", false, true);
        conversation("Đã xoá", "Hỏi cũ", "Trả lời cũ", true, false);
        var page = history.page(new ActorId(reader), all(), null, 30);
        assertEquals(List.of("Đã xoá"), page.items().stream().map(ChatHistoryService.Conversation::title).toList());
        assertTrue(page.items().getFirst().deleted(), "a deleted conversation says so, as Onyx does not");
    }

    @Test void hidingWhoAskedDropsTheirNameAndRefusesToReadOnePersonsHistory() {
        var session = conversation("Nghỉ phép", "Tôi còn bao nhiêu ngày phép?", "Bạn còn 5 ngày.", false, false);
        when(settings.historyVisibility(any())).thenReturn(ChatHistoryVisibility.ANONYMIZED);
        var entry = history.page(new ActorId(reader), all(), null, 30).items().getFirst();
        assertNull(entry.person());
        assertNull(entry.email());
        assertNull(entry.actorId());
        // The question and the answer are not hidden; only who asked is. The screen says as much.
        assertEquals("Tôi còn bao nhiêu ngày phép?", entry.question());
        assertThrows(ChatException.class, () -> history.page(new ActorId(reader),
                new ChatHistoryQuery(null, null, null, asker, null), null, 30));
        assertNotNull(history.transcript(new ActorId(reader), session).messages());
    }

    @Test void turningHistoryOffRefusesEveryRead() {
        var session = conversation("Nghỉ phép", "Hỏi", "Đáp", false, false);
        when(settings.historyVisibility(any())).thenReturn(ChatHistoryVisibility.DISABLED);
        assertThrows(ChatException.class, () -> history.page(new ActorId(reader), all(), null, 30));
        assertThrows(ChatException.class, () -> history.transcript(new ActorId(reader), session));
        assertThrows(ChatException.class, () -> history.export(new ActorId(reader), all(), ignored -> { }));
    }

    @Test void aTranscriptReadsTheSelectedBranchWithItsFeedbackAndCitedTitles() {
        var session = conversation("Hợp đồng", "Điều khoản phạt là gì?", "Phạt 8% giá trị.", false, false);
        rate(session, false);
        var transcript = history.transcript(new ActorId(reader), session);
        assertEquals(List.of("USER", "ASSISTANT"),
                transcript.messages().stream().map(ChatHistoryMessage::role).toList());
        var answer = transcript.messages().get(1);
        assertEquals(List.of("Hợp đồng Tasco 2026"), answer.citations(), "a citation is named, never opened here");
        assertEquals(Boolean.FALSE, answer.positive());
    }

    @Test void theFeedbackFilterSelectsOnlyTheConversationsRatedThatWay() {
        var liked = conversation("Tốt", "Hỏi 1", "Đáp 1", false, false);
        rate(liked, true);
        var disliked = conversation("Chưa tốt", "Hỏi 2", "Đáp 2", false, false);
        rate(disliked, false);
        conversation("Chưa chấm", "Hỏi 3", "Đáp 3", false, false);
        assertEquals(List.of("Chưa tốt"), titles(new ChatHistoryQuery(null, null, null, null, ChatHistoryFeedback.NEGATIVE)));
        assertEquals(List.of("Chưa chấm"), titles(new ChatHistoryQuery(null, null, null, null, ChatHistoryFeedback.NONE)));
    }

    @Test void theCursorWalksEveryConversationOnceAndTheSearchIsLiteral() {
        conversation("Một", "Hỏi 1", "Đáp", false, false);
        conversation("Hai", "Hỏi 2", "Đáp", false, false);
        conversation("100% chắc", "Hỏi 3", "Đáp", false, false);
        var seen = new ArrayList<String>();
        String cursor = null;
        do {
            var page = history.page(new ActorId(reader), all(), cursor, 2);
            page.items().forEach(item -> seen.add(item.title()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(3, seen.size());
        assertEquals(3, seen.stream().distinct().count());
        // "100%" is text to find, not a pattern that matches everything.
        assertEquals(List.of("100% chắc"), titles(new ChatHistoryQuery(null, null, "100%", null, null)));
    }

    @Test void theExportCarriesEveryConversationTheFiltersSelect() {
        conversation("Một", "Hỏi 1", "Đáp", false, false);
        conversation("Hai", "Hỏi 2", "Đáp", false, false);
        var rows = new ArrayList<String>();
        int exported = history.export(new ActorId(reader), all(), row -> rows.add(row.title()));
        assertEquals(2, exported);
        assertEquals(2, rows.size());
    }

    private List<String> titles(ChatHistoryQuery query) {
        return history.page(new ActorId(reader), query, null, 30).items().stream()
                .map(ChatHistoryService.Conversation::title).toList();
    }

    private static ChatHistoryQuery all() {
        return new ChatHistoryQuery(null, null, null, null, null);
    }

    private IamAuthorization authorization() {
        var authorization = mock(IamAuthorization.class);
        when(authorization.require(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new IamAccess(new TenantId(tenant), Authority.GLOBAL));
        return authorization;
    }

    private UUID person(String name, String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", id).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant).param("actor", id).update();
        jdbc.sql("INSERT INTO external_identity_bindings(issuer,subject,actor_id) VALUES('https://idp.test',CAST(:id AS TEXT),:id)")
                .param("id", id).update();
        jdbc.sql("""
                        INSERT INTO actor_profiles(actor_id,issuer,subject,display_name,email,email_verified,observed_at)
                        VALUES(:id,'https://idp.test',CAST(:id AS TEXT),:name,:email,TRUE,CURRENT_TIMESTAMP)
                        """)
                .param("id", id).param("name", name).param("email", email).update();
        return id;
    }

    /** One question and one answer, as the asker's own history would read them. */
    private UUID conversation(String title, String question, String answer, boolean deleted, boolean temporary) {
        UUID session = UUID.randomUUID();
        UUID root = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID assistant = UUID.randomUUID();
        // chat_session and its root message reference each other; the deferred constraint needs one transaction.
        tx.executeWithoutResult(ignored -> {
        jdbc.sql("""
                        INSERT INTO chat_session(id,tenant_id,owner_actor_id,persona_id,root_message_id,title,
                                                 deleted_at,temporary)
                        VALUES(:id,:tenant,:actor,:persona,:root,:title,
                               CASE WHEN :deleted THEN CURRENT_TIMESTAMP END,:temporary)
                        """)
                .param("id", session).param("tenant", tenant).param("actor", asker).param("persona", persona)
                .param("root", root).param("title", title).param("deleted", deleted).param("temporary", temporary)
                .update();
        message(session, root, null, "ROOT", "", null, null, null, null);
        message(session, user, root, "USER", question, null, null, UUID.randomUUID(), assistant);
        message(session, assistant, user, "ASSISTANT", answer, "gpt-5.1",
                "[{\"citationId\":1,\"title\":\"Hợp đồng Tasco 2026\",\"startOrdinal\":0,\"endOrdinal\":0,"
                        + "\"provenance\":[],\"sourceTypes\":[]}]", null, null);
        jdbc.sql("UPDATE chat_message SET latest_child_message_id = :child WHERE id = :id")
                .param("child", user).param("id", root).update();
        jdbc.sql("UPDATE chat_message SET latest_child_message_id = :child WHERE id = :id")
                .param("child", assistant).param("id", user).update();
        });
        return session;
    }

    private void message(UUID session, UUID id, @Nullable UUID parent, String role, String content,
                         @Nullable String model, @Nullable String sources, @Nullable UUID request,
                         @Nullable UUID answeredBy) {
        jdbc.sql("""
                        INSERT INTO chat_message(id,session_id,parent_message_id,role,status,content,model_name,
                                                 finished_at,deadline_at,sources,client_request_id,
                                                 original_assistant_message_id)
                        VALUES(:id,:session,:parent,:role,'COMPLETED',:content,:model,CURRENT_TIMESTAMP,
                               CASE WHEN :role = 'ASSISTANT' THEN CURRENT_TIMESTAMP END,
                               CAST(COALESCE(:sources,'[]') AS jsonb),:request,:answeredBy)
                        """)
                .param("id", id).param("session", session).param("parent", parent, java.sql.Types.OTHER)
                .param("role", role).param("content", content).param("model", model, java.sql.Types.VARCHAR)
                .param("sources", sources, java.sql.Types.VARCHAR)
                .param("request", request, java.sql.Types.OTHER)
                .param("answeredBy", answeredBy, java.sql.Types.OTHER).update();
    }

    private void rate(UUID session, boolean positive) {
        UUID assistant = jdbc.sql("SELECT id FROM chat_message WHERE session_id = :session AND role = 'ASSISTANT'")
                .param("session", session).query(UUID.class).single();
        jdbc.sql("""
                        INSERT INTO chat_feedback(id,tenant_id,actor_id,session_id,assistant_message_id,positive,comment)
                        VALUES(:id,:tenant,:actor,:session,:message,:positive,'')
                        """)
                .param("id", UUID.randomUUID()).param("tenant", tenant).param("actor", asker)
                .param("session", session).param("message", assistant).param("positive", positive).update();
    }

    @Test void aPageSizeOutsideTheBoundIsRefused() {
        assertThrows(ChatException.class, () -> history.page(new ActorId(reader), all(), null, 0));
        assertThrows(ChatException.class, () -> history.page(new ActorId(reader), all(), null, 101));
        assertThrows(ChatException.class, () -> history.page(new ActorId(reader), all(), "not-a-cursor", 30));
    }
}
