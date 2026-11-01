package io.memoryos.chat.preferences.persistence;

import io.memoryos.chat.ChatMemory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** One member's memory document (MEM-250); a missing row is {@link ChatMemory#EMPTY}. */
@Repository
public class JdbcChatMemoryRepository {
    private static final String COLUMNS = "content, enabled, revision";
    private final JdbcClient jdbc;

    public JdbcChatMemoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ChatMemory find(UUID tenant, UUID actor) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM chat_memory WHERE tenant_id = :tenant AND actor_id = :actor")
                .param("tenant", tenant).param("actor", actor)
                .query((row, _) -> map(row)).optional().orElse(ChatMemory.EMPTY);
    }

    /**
     * Replaces the text when the stored revision is still {@code expected}, and advances it; empty when another
     * write came first. A member without a row has revision 0, so only {@code expected} 0 creates one. With
     * {@code onlyEnabled} the write also needs the memory to be switched on when it lands, so the tool cannot write
     * after the member switched it off; the member's own edits in Settings pass false.
     */
    public Optional<ChatMemory> replace(UUID tenant, UUID actor, String content, long expected, boolean onlyEnabled) {
        if (expected == 0) {
            return jdbc.sql("""
                            INSERT INTO chat_memory (tenant_id, actor_id, content, revision, updated_at)
                            VALUES (:tenant, :actor, :content, 1, CURRENT_TIMESTAMP)
                            ON CONFLICT (tenant_id, actor_id) DO UPDATE SET
                                content = EXCLUDED.content, revision = chat_memory.revision + 1,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE chat_memory.revision = 0 AND (chat_memory.enabled OR NOT :onlyEnabled)
                            RETURNING\s""" + COLUMNS)
                    .param("tenant", tenant).param("actor", actor).param("content", content)
                    .param("onlyEnabled", onlyEnabled)
                    .query((row, _) -> map(row)).optional();
        }
        return jdbc.sql("""
                        UPDATE chat_memory SET content = :content, revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenant AND actor_id = :actor AND revision = :expected
                            AND (enabled OR NOT :onlyEnabled)
                        RETURNING\s""" + COLUMNS)
                .param("tenant", tenant).param("actor", actor).param("content", content).param("expected", expected)
                .param("onlyEnabled", onlyEnabled)
                .query((row, _) -> map(row)).optional();
    }

    /** Turns the memory on or off; the text and its revision are kept. */
    public ChatMemory enabled(UUID tenant, UUID actor, boolean enabled) {
        return jdbc.sql("""
                        INSERT INTO chat_memory (tenant_id, actor_id, enabled) VALUES (:tenant, :actor, :enabled)
                        ON CONFLICT (tenant_id, actor_id) DO UPDATE SET enabled = EXCLUDED.enabled
                        RETURNING\s""" + COLUMNS)
                .param("tenant", tenant).param("actor", actor).param("enabled", enabled)
                .query((row, _) -> map(row)).single();
    }

    /**
     * Whether a turn in this conversation may read and write the owner's memory: switched on, not temporary, and held
     * in the web app. A conversation held in a channel (MEM-246) can be a group chat, where the owner's memory must
     * neither show nor be written from what others say.
     */
    public boolean usableIn(UUID tenant, UUID actor, UUID session) {
        return jdbc.sql("""
                        SELECT NOT s.temporary AND s.surface = 'WEB' AND COALESCE(m.enabled, TRUE)
                        FROM chat_session s
                        LEFT JOIN chat_memory m ON m.tenant_id = s.tenant_id AND m.actor_id = s.owner_actor_id
                        WHERE s.id = :session AND s.tenant_id = :tenant AND s.owner_actor_id = :actor
                        """)
                .param("tenant", tenant).param("actor", actor).param("session", session)
                .query(Boolean.class).optional().orElse(false);
    }

    private static ChatMemory map(ResultSet row) throws SQLException {
        return new ChatMemory(row.getString(1), row.getBoolean(2), row.getLong(3));
    }
}
