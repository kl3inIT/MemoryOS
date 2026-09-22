package io.memoryos.chat.persistence;

import java.time.Duration;
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
     * Deleted conversations past their waiting window, with no reply still running, locked for this
     * transaction. A cancelled reply's terminal write can still be in flight, and a purge must never race that
     * writer. A temporary conversation waits for nothing: it deletes itself precisely so that it leaves no
     * trace, and the window exists for conversations someone might ask about.
     */
    public List<UUID> claim(int limit, Duration deletedAfter) {
        return jdbc.sql("""
                SELECT s.id FROM chat_session s
                WHERE s.deleted_at IS NOT NULL
                  AND (s.temporary OR s.deleted_at <= CURRENT_TIMESTAMP - make_interval(secs => :after))
                  AND NOT EXISTS (SELECT 1 FROM chat_message m WHERE m.session_id = s.id AND m.status = 'RUNNING')
                ORDER BY s.deleted_at
                LIMIT :limit FOR UPDATE OF s SKIP LOCKED
                """).param("limit", limit).param("after", (double) deletedAfter.toMillis() / 1000)
                .query(UUID.class).list();
    }

    /** How many deleted conversations are still inside their window, for the sweep's own log line. */
    public long awaiting(Duration deletedAfter) {
        return jdbc.sql("""
                SELECT count(*) FROM chat_session s
                WHERE s.deleted_at IS NOT NULL AND NOT s.temporary
                  AND s.deleted_at > CURRENT_TIMESTAMP - make_interval(secs => :after)
                """).param("after", (double) deletedAfter.toMillis() / 1000).query(Long.class).single();
    }

    /**
     * Deletes temporary conversations whose last message is older than the window, which is what makes them
     * temporary; the purge above then removes their rows and hands their uploads to the file work.
     */
    public int expireTemporary(Duration after, int limit) {
        return jdbc.sql("""
                UPDATE chat_session SET deleted_at = CURRENT_TIMESTAMP
                WHERE id IN (
                    SELECT s.id FROM chat_session s
                    WHERE s.temporary AND s.deleted_at IS NULL
                      AND s.updated_at <= CURRENT_TIMESTAMP - make_interval(secs => :after)
                      AND NOT EXISTS (SELECT 1 FROM chat_message m
                                      WHERE m.session_id = s.id AND m.status = 'RUNNING')
                    ORDER BY s.updated_at LIMIT :limit)
                """).param("after", (double) after.toMillis() / 1000).param("limit", limit).update();
    }

    /**
     * Deletes the conversations of one Tenant whose last activity is older than its retention policy, in
     * batches; the purge then treats them exactly like a conversation someone deleted.
     */
    public int applyRetention(java.util.UUID tenant, java.util.UUID owner, int days, int limit) {
        return jdbc.sql("""
                UPDATE chat_session SET deleted_at = CURRENT_TIMESTAMP
                WHERE id IN (
                    SELECT s.id FROM chat_session s
                    WHERE s.tenant_id = :tenant AND s.owner_actor_id = :owner AND s.deleted_at IS NULL
                      AND s.updated_at <= CURRENT_TIMESTAMP - make_interval(days => :days)
                      AND NOT EXISTS (SELECT 1 FROM chat_message m
                                      WHERE m.session_id = s.id AND m.status = 'RUNNING')
                    ORDER BY s.updated_at LIMIT :limit)
                """).param("tenant", tenant).param("owner", owner).param("days", days).param("limit", limit)
                .update();
    }

    /** Every person who keeps their conversations for a limited time, with the number of days they chose. */
    public List<Policy> policies() {
        return jdbc.sql("""
                SELECT tenant_id, actor_id, retention_days FROM chat_preferences WHERE retention_days IS NOT NULL
                """).query((row, ignored) -> new Policy(row.getObject("tenant_id", UUID.class),
                        row.getObject("actor_id", UUID.class), row.getInt("retention_days"))).list();
    }

    public record Policy(UUID tenant, UUID owner, int days) {}

    /**
     * How many of this person's own conversations a policy of {@code days} would delete, so they see the size
     * of the change before saving it. A temporary conversation is not counted: it deletes itself anyway.
     */
    public long affectedByRetention(java.util.UUID tenant, java.util.UUID owner, int days) {
        return jdbc.sql("""
                SELECT count(*) FROM chat_session s
                WHERE s.tenant_id = :tenant AND s.owner_actor_id = :owner AND s.deleted_at IS NULL
                  AND NOT s.temporary
                  AND s.updated_at <= CURRENT_TIMESTAMP - make_interval(days => :days)
                """).param("tenant", tenant).param("owner", owner).param("days", days).query(Long.class).single();
    }

    /**
     * Hands the uploads that belong to a temporary conversation to the file work, which releases their bytes
     * the way a deleted upload's are released. An ordinary conversation's uploads are never touched.
     */
    public int releaseTemporaryUploads(UUID session) {
        // The same two steps a deleted upload takes: stop the work it has queued, then queue its deletion, so
        // the existing file worker releases the bytes rather than a second release path doing it here.
        jdbc.sql("""
                UPDATE chat_file_work SET status='CANCELLED', claim_token=NULL, lease_expires_at=NULL,
                    dispatch_token=NULL, dispatch_lease_expires_at=NULL, completed_at=CURRENT_TIMESTAMP
                WHERE status IN ('NOT_STARTED','IN_PROGRESS') AND file_id IN (
                    SELECT id FROM chat_user_file WHERE temporary_session_id = :session)
                """).param("session", session).update();
        var released = jdbc.sql("""
                UPDATE chat_user_file SET status = 'DELETING', updated_at = CURRENT_TIMESTAMP
                WHERE temporary_session_id = :session AND status NOT IN ('DELETING', 'DELETED')
                RETURNING id, tenant_id
                """).param("session", session)
                .query((row, ignored) -> new java.util.AbstractMap.SimpleEntry<>(
                        row.getObject("id", UUID.class), row.getObject("tenant_id", UUID.class)))
                .list();
        for (var file : released) {
            jdbc.sql("""
                    INSERT INTO chat_file_work(id, tenant_id, file_id, action)
                    VALUES (:id, :tenant, :file, 'DELETE')
                    """).param("id", UUID.randomUUID()).param("tenant", file.getValue())
                    .param("file", file.getKey()).update();
        }
        return released.size();
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
