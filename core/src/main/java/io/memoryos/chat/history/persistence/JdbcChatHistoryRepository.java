package io.memoryos.chat.history.persistence;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.memoryos.shared.LikePattern;
import io.memoryos.chat.ChatHistoryFeedback;
import io.memoryos.chat.ChatHistoryMessage;
import io.memoryos.chat.ChatHistoryQuery;
import io.memoryos.chat.ChatHistoryTotals;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The Tenant's conversations, read for an administrator (MEM-125). A temporary conversation never appears: its
 * content is not written at all. A conversation its owner deleted does appear, as in Onyx, and says so.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChatHistoryRepository {
    private final JdbcClient jdbc;

    public JdbcChatHistoryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** One conversation as the list shows it; the asker is dropped later when the Tenant hides them. */
    public record Entry(UUID id, UUID rootMessageId, @Nullable UUID actorId, @Nullable String actorLabel, @Nullable String actorEmail,
                        String title, @Nullable String firstQuestion, @Nullable String firstAnswer,
                        @Nullable String modelName, long messages, ChatHistoryFeedback feedback, boolean deleted,
                        Instant updatedAt) {}

    /** The page cursor: a conversation's own (updated_at, id), so a page never repeats or skips one. */
    public record Cursor(Instant updatedAt, UUID id) {}

    private static final String FEEDBACK = """
            CASE
                WHEN COUNT(f.positive) FILTER (WHERE f.positive IS NOT NULL) = 0 THEN 'NONE'
                WHEN bool_and(f.positive) FILTER (WHERE f.positive IS NOT NULL) THEN 'POSITIVE'
                WHEN bool_or(f.positive) FILTER (WHERE f.positive IS NOT NULL) THEN 'MIXED'
                ELSE 'NEGATIVE'
            END
            """;

    private static final String PAGE = """
            SELECT s.id, s.root_message_id, s.owner_actor_id, p.display_name, p.email, s.title, s.updated_at,
                   s.deleted_at IS NOT NULL AS deleted,
                   (SELECT m.content FROM chat_message m
                     WHERE m.session_id = s.id AND m.role = 'USER' ORDER BY m.created_at, m.id LIMIT 1) AS question,
                   (SELECT m.content FROM chat_message m
                     WHERE m.session_id = s.id AND m.role = 'ASSISTANT' AND m.status = 'COMPLETED'
                     ORDER BY m.created_at, m.id LIMIT 1) AS answer,
                   (SELECT m.model_name FROM chat_message m
                     WHERE m.session_id = s.id AND m.model_name IS NOT NULL
                     ORDER BY m.created_at DESC, m.id DESC LIMIT 1) AS model_name,
                   (SELECT COUNT(*) FROM chat_message m WHERE m.session_id = s.id AND m.role <> 'ROOT') AS messages,
                   (SELECT\s""" + FEEDBACK + """
                      FROM chat_feedback f WHERE f.session_id = s.id) AS feedback
            FROM chat_session s
            LEFT JOIN actor_profiles p ON p.actor_id = s.owner_actor_id
            WHERE s.tenant_id = :tenant AND NOT s.temporary
              AND (CAST(:from AS timestamptz) IS NULL OR s.updated_at >= :from)
              AND (CAST(:to AS timestamptz) IS NULL OR s.updated_at <= :to)
              AND (CAST(:actor AS uuid) IS NULL OR s.owner_actor_id = :actor)
              AND (CAST(:text AS varchar) IS NULL OR s.title ILIKE :text
                   OR p.display_name ILIKE :text OR p.email ILIKE :text)
              AND (CAST(:feedback AS varchar) IS NULL OR (SELECT\s""" + FEEDBACK + """
                   FROM chat_feedback f WHERE f.session_id = s.id) = :feedback)
              AND (CAST(:cursorAt AS timestamptz) IS NULL
                   OR (s.updated_at, s.id) < (:cursorAt, CAST(:cursorId AS uuid)))
            ORDER BY s.updated_at DESC, s.id DESC
            LIMIT :limit
            """;

    public List<Entry> page(UUID tenant, ChatHistoryQuery query, @Nullable Cursor after, int limit) {
        return bind(jdbc.sql(PAGE), tenant, query)
                .param("cursorAt", after == null ? null : Timestamp.from(after.updatedAt()), Types.TIMESTAMP)
                .param("cursorId", after == null ? null : after.id(), Types.OTHER)
                .param("limit", limit)
                .query(JdbcChatHistoryRepository::entry).list();
    }

    public ChatHistoryTotals totals(UUID tenant, ChatHistoryQuery query) {
        return bind(jdbc.sql("SELECT COUNT(*) AS conversations,"
                + " COUNT(*) FILTER (WHERE page.feedback = 'POSITIVE') AS positive,"
                + " COUNT(*) FILTER (WHERE page.feedback IN ('NEGATIVE', 'MIXED')) AS negative"
                + " FROM (" + PAGE.replace("LIMIT :limit", "") + ") page"), tenant, query)
                .param("cursorAt", null, Types.TIMESTAMP).param("cursorId", null, Types.OTHER)
                .param("limit", Integer.MAX_VALUE)
                .query((row, ignored) -> new ChatHistoryTotals(row.getLong("conversations"), row.getLong("positive"),
                        row.getLong("negative")))
                .single();
    }

    /** One conversation, whether or not its owner deleted it, so the detail view can say which it is. */
    public Optional<Entry> conversation(UUID tenant, UUID session) {
        return bind(jdbc.sql(PAGE.replace("ORDER BY s.updated_at DESC, s.id DESC", "AND s.id = :session ORDER BY s.updated_at DESC, s.id DESC")
                        .replace("LIMIT :limit", "")), tenant, new ChatHistoryQuery(null, null, null, null, null))
                .param("cursorAt", null, Types.TIMESTAMP).param("cursorId", null, Types.OTHER)
                .param("session", session)
                .query(JdbcChatHistoryRepository::entry).optional();
    }

    /**
     * The transcript of the branch the owner has selected, as their own history reads it, with each answer's
     * feedback and the titles it cited. Citation bodies are not read here: an administrator opens a source through
     * their own authority, never the asker's.
     */
    public List<ChatHistoryMessage> transcript(UUID session, UUID rootMessageId, int limit) {
        return jdbc.sql("""
                        WITH RECURSIVE branch AS (
                            SELECT m.*, 0 AS depth FROM chat_message m WHERE m.session_id = :session AND m.id = :root
                            UNION ALL
                            SELECT m.*, b.depth + 1 FROM branch b
                            JOIN chat_message m ON m.id = b.latest_child_message_id AND m.session_id = :session
                            WHERE b.depth < :limit
                        )
                        SELECT b.id, b.role, b.content, b.model_name, b.created_at, b.sources,
                               (SELECT bool_and(f.positive) FROM chat_feedback f
                                 WHERE f.assistant_message_id = b.id AND f.positive IS NOT NULL) AS positive,
                               (SELECT string_agg(NULLIF(f.comment, ''), ' · ') FROM chat_feedback f
                                 WHERE f.assistant_message_id = b.id) AS comment
                        FROM branch b WHERE b.depth > 0 ORDER BY b.depth
                        """)
                .param("session", session).param("root", rootMessageId).param("limit", limit)
                .query(JdbcChatHistoryRepository::message).list();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, UUID tenant, ChatHistoryQuery query) {
        return statement.param("tenant", tenant)
                .param("from", query.from() == null ? null : Timestamp.from(query.from()), Types.TIMESTAMP)
                .param("to", query.to() == null ? null : Timestamp.from(query.to()), Types.TIMESTAMP)
                .param("actor", query.actorId(), Types.OTHER)
                .param("text", query.text() == null ? null : LikePattern.containing(query.text()), Types.VARCHAR)
                .param("feedback", query.feedback() == null ? null : query.feedback().name(), Types.VARCHAR);
    }

    private static Entry entry(ResultSet row, int ignored) throws SQLException {
        return new Entry(row.getObject("id", UUID.class), row.getObject("root_message_id", UUID.class),
                row.getObject("owner_actor_id", UUID.class),
                row.getString("display_name"), row.getString("email"), row.getString("title"),
                row.getString("question"), row.getString("answer"), row.getString("model_name"),
                row.getLong("messages"), ChatHistoryFeedback.valueOf(row.getString("feedback")), row.getBoolean("deleted"),
                row.getTimestamp("updated_at").toInstant());
    }

    private static ChatHistoryMessage message(ResultSet row, int ignored) throws SQLException {
        return new ChatHistoryMessage(row.getObject("id", UUID.class), row.getString("role"), row.getString("content"),
                row.getString("model_name"), row.getTimestamp("created_at").toInstant(),
                row.getObject("positive", Boolean.class), row.getString("comment"),
                citations(row.getString("sources")));
    }

    private static final ObjectMapper JSON =
            new ObjectMapper();

    /** Only the titles: what was cited is evidence, its content is not this screen's to hand over. */
    private static List<String> citations(@Nullable String sources) {
        if (sources == null || sources.isBlank()) return List.of();
        try {
            var titles = new ArrayList<String>();
            for (var node : JSON.readTree(sources)) {
                var title = node.path("title").asText("");
                if (!title.isBlank()) titles.add(title);
            }
            return List.copyOf(titles);
        } catch (JacksonException malformed) {
            return List.of();
        }
    }
}
