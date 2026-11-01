package io.memoryos.chat.session.persistence;

import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** MEM-268: the summaries that stand in for a conversation's earlier turns, kept by branch. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChatSummaryRepository {
    private final JdbcClient jdbc;

    public JdbcChatSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A summary as a turn replays it: the text and the last message it covers. */
    public record Summary(UUID cutoffMessageId, String content) {
    }

    public UUID insert(TenantId tenant, UUID session, UUID parent, UUID cutoff, String content, int tokenCount,
                       @Nullable UUID modelConfigurationId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO chat_session_summary(id, tenant_id, session_id, parent_message_id, cutoff_message_id,
                                                 content, token_count, model_configuration_id)
                VALUES (:id, :tenant, :session, :parent, :cutoff, :content, :tokens, :model)
                """).param("id", id).param("tenant", tenant.value()).param("session", session)
                .param("parent", parent).param("cutoff", cutoff).param("content", content)
                .param("tokens", tokenCount).param("model", modelConfigurationId).update();
        return id;
    }

    /**
     * Claims the session's compaction for {@code hold}: true when no compaction holds it and no failed one asked to
     * wait. A claim whose holder died lapses with its hold.
     */
    public boolean claim(TenantId tenant, UUID session, Duration hold) {
        return jdbc.sql("""
                INSERT INTO chat_session_compaction(session_id, tenant_id, claimed_until)
                VALUES (:session, :tenant, CURRENT_TIMESTAMP + make_interval(secs => :hold))
                ON CONFLICT (session_id) DO UPDATE SET claimed_until = EXCLUDED.claimed_until
                WHERE (chat_session_compaction.claimed_until IS NULL OR chat_session_compaction.claimed_until < CURRENT_TIMESTAMP)
                  AND (chat_session_compaction.retry_after IS NULL OR chat_session_compaction.retry_after <= CURRENT_TIMESTAMP)
                """).param("session", session).param("tenant", tenant.value()).param("hold", hold.toSeconds())
                .update() == 1;
    }

    /** Ends the session's claim; a failed compaction is not tried again before {@code retryAfter} has passed. */
    public void release(UUID session, @Nullable Duration retryAfter) {
        if (retryAfter == null) {
            jdbc.sql("UPDATE chat_session_compaction SET claimed_until = NULL, retry_after = NULL WHERE session_id = :session")
                    .param("session", session).update();
            return;
        }
        jdbc.sql("""
                UPDATE chat_session_compaction
                SET claimed_until = NULL, retry_after = CURRENT_TIMESTAMP + make_interval(secs => :retry)
                WHERE session_id = :session
                """).param("session", session).param("retry", retryAfter.toSeconds()).update();
    }

    /**
     * The summary a turn asked by {@code user} replays: its parent is the nearest ancestor of the question and its
     * cutoff is on the same branch. Onyx's {@code load_branch_summary}: a summary of another branch, or one whose cutoff
     * an edit or a regeneration left behind, is never found.
     */
    public Optional<Summary> forTurn(TenantId tenant, UUID session, UUID user) {
        return jdbc.sql("""
                WITH RECURSIVE branch AS (
                    SELECT id, parent_message_id, 0 AS depth FROM chat_message WHERE session_id = :session AND id = :user
                    UNION ALL
                    SELECT m.id, m.parent_message_id, b.depth + 1 FROM branch b
                    JOIN chat_message m ON m.session_id = :session AND m.id = b.parent_message_id
                )
                SELECT s.cutoff_message_id, s.content FROM chat_session_summary s
                JOIN branch parent ON parent.id = s.parent_message_id
                WHERE s.tenant_id = :tenant AND s.session_id = :session
                  AND EXISTS (SELECT 1 FROM branch cutoff WHERE cutoff.id = s.cutoff_message_id)
                ORDER BY parent.depth, s.created_at DESC, s.id
                LIMIT 1
                """).param("tenant", tenant.value()).param("session", session).param("user", user)
                .query((row, ignored) -> new Summary(row.getObject("cutoff_message_id", UUID.class),
                        row.getString("content"))).optional();
    }
}
