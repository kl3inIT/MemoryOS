package io.memoryos.chat.history.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
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

    /** How a conversation's readers rated its answers, rolled up as Onyx rolls a session's feedback up. */
    public enum Feedback { POSITIVE, NEGATIVE, MIXED, NONE }

    /** One conversation as the list shows it; the asker is dropped later when the Tenant hides them. */
    public record Entry(UUID id, UUID rootMessageId, @Nullable UUID actorId, @Nullable String actorLabel, @Nullable String actorEmail,
                        String title, @Nullable String firstQuestion, @Nullable String firstAnswer,
                        @Nullable String modelName, long messages, Feedback feedback, boolean deleted,
                        Instant updatedAt) {}

    /** One message of a transcript, with the feedback its readers left and the titles it cited. */
    public record Message(UUID id, String role, String content, @Nullable String modelName, Instant createdAt,
                          @Nullable Boolean positive, @Nullable String comment, List<String> citations) {}

    /** The filters the screen offers; every one narrows the same page query. */
    public record Query(@Nullable Instant from, @Nullable Instant to, @Nullable String text, @Nullable UUID actorId,
                        @Nullable Feedback feedback) {}

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

    public List<Entry> page(UUID tenant, Query query, @Nullable Cursor after, int limit) {
        return bind(jdbc.sql(PAGE), tenant, query)
                .param("cursorAt", after == null ? null : java.sql.Timestamp.from(after.updatedAt()), Types.TIMESTAMP)
                .param("cursorId", after == null ? null : after.id(), Types.OTHER)
                .param("limit", limit)
                .query(JdbcChatHistoryRepository::entry).list();
    }

    /** The counts above the list: how much was asked in this period, and how it was rated. */
    public record Totals(long conversations, long positive, long negative) {}

    public Totals totals(UUID tenant, Query query) {
        return bind(jdbc.sql("SELECT COUNT(*) AS conversations,"
                + " COUNT(*) FILTER (WHERE page.feedback = 'POSITIVE') AS positive,"
                + " COUNT(*) FILTER (WHERE page.feedback IN ('NEGATIVE', 'MIXED')) AS negative"
                + " FROM (" + PAGE.replace("LIMIT :limit", "") + ") page"), tenant, query)
                .param("cursorAt", null, Types.TIMESTAMP).param("cursorId", null, Types.OTHER)
                .param("limit", Integer.MAX_VALUE)
                .query((row, ignored) -> new Totals(row.getLong("conversations"), row.getLong("positive"),
                        row.getLong("negative")))
                .single();
    }

    /** One conversation, whether or not its owner deleted it, so the detail view can say which it is. */
    public java.util.Optional<Entry> conversation(UUID tenant, UUID session) {
        return bind(jdbc.sql(PAGE.replace("LIMIT :limit", "") + " AND s.id = :session"), tenant,
                new Query(null, null, null, null, null))
                .param("cursorAt", null, Types.TIMESTAMP).param("cursorId", null, Types.OTHER)
                .param("session", session)
                .query(JdbcChatHistoryRepository::entry).optional();
    }

    /**
     * The transcript of the branch the owner has selected, as their own history reads it, with each answer's
     * feedback and the titles it cited. Citation bodies are not read here: an administrator opens a source through
     * their own authority, never the asker's.
     */
    public List<Message> transcript(UUID session, UUID rootMessageId, int limit) {
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

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, UUID tenant, Query query) {
        return statement.param("tenant", tenant)
                .param("from", query.from() == null ? null : java.sql.Timestamp.from(query.from()), Types.TIMESTAMP)
                .param("to", query.to() == null ? null : java.sql.Timestamp.from(query.to()), Types.TIMESTAMP)
                .param("actor", query.actorId(), Types.OTHER)
                .param("text", query.text() == null ? null : "%" + escaped(query.text()) + "%", Types.VARCHAR)
                .param("feedback", query.feedback() == null ? null : query.feedback().name(), Types.VARCHAR);
    }

    /** A search for "100%" is a search for that text, not for every conversation. */
    private static String escaped(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Entry entry(ResultSet row, int ignored) throws SQLException {
        return new Entry(row.getObject("id", UUID.class), row.getObject("root_message_id", UUID.class),
                row.getObject("owner_actor_id", UUID.class),
                row.getString("display_name"), row.getString("email"), row.getString("title"),
                row.getString("question"), row.getString("answer"), row.getString("model_name"),
                row.getLong("messages"), Feedback.valueOf(row.getString("feedback")), row.getBoolean("deleted"),
                row.getTimestamp("updated_at").toInstant());
    }

    private static Message message(ResultSet row, int ignored) throws SQLException {
        return new Message(row.getObject("id", UUID.class), row.getString("role"), row.getString("content"),
                row.getString("model_name"), row.getTimestamp("created_at").toInstant(),
                row.getObject("positive", Boolean.class), row.getString("comment"),
                citations(row.getString("sources")));
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Only the titles: what was cited is evidence, its content is not this screen's to hand over. */
    private static List<String> citations(@Nullable String sources) {
        if (sources == null || sources.isBlank()) return List.of();
        try {
            var titles = new java.util.ArrayList<String>();
            for (var node : JSON.readTree(sources)) {
                var title = node.path("title").asText("");
                if (!title.isBlank()) titles.add(title);
            }
            return List.copyOf(titles);
        } catch (com.fasterxml.jackson.core.JacksonException malformed) {
            return List.of();
        }
    }
}
