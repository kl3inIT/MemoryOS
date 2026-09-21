package io.memoryos.chat.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Removes conversations their owner already deleted (MEM-143). The claim is the session row itself: a purge
 * that fails rolls back and leaves the row soft-deleted for the next run, so no claim column is needed.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChatSessionPurgeRepository {
    private final JdbcClient jdbc;

    public JdbcChatSessionPurgeRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * Deleted conversations with no reply still running, locked for this transaction. A cancelled reply's
     * terminal write can still be in flight, and a purge must never race that writer.
     */
    public List<UUID> claim(int limit) {
        return jdbc.sql("""
                SELECT s.id FROM chat_session s
                WHERE s.deleted_at IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM chat_message m WHERE m.session_id = s.id AND m.status = 'RUNNING')
                ORDER BY s.deleted_at
                LIMIT :limit FOR UPDATE OF s SKIP LOCKED
                """).param("limit", limit).query(UUID.class).list();
    }

    /**
     * Hands the conversation's generated files and images to the MEM-142 cleanup sweep, which owns the byte
     * release, and returns how many artifacts it marked.
     */
    public int releaseArtifacts(UUID session) {
        int files = jdbc.sql("""
                UPDATE chat_file_artifact SET deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP)
                WHERE session_id = :session
                """).param("session", session).update();
        int images = jdbc.sql("""
                UPDATE chat_image_artifact SET deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP)
                WHERE session_id = :session
                """).param("session", session).update();
        return files + images;
    }

    /**
     * Deletes the conversation. The cascades from {@code chat_session} would remove the same rows, but the
     * order is explicit so a reference between two cascading tables cannot decide it. Uploads are never
     * touched: an upload belongs to its owner and to the library, not to one conversation (Onyx skips
     * {@code user_file_id} for the same reason).
     */
    public void purge(UUID session) {
        for (String table : List.of("chat_command", "chat_feedback", "chat_sharing")) {
            jdbc.sql("DELETE FROM " + table + " WHERE session_id = :session").param("session", session).update();
        }
        // chat_session points at its root message and the messages point at each other; both constraints are
        // deferred, so the rows may go in this order as long as one transaction ends with neither side left.
        jdbc.sql("DELETE FROM chat_message WHERE session_id = :session").param("session", session).update();
        jdbc.sql("DELETE FROM chat_session WHERE id = :session").param("session", session).update();
    }
}
