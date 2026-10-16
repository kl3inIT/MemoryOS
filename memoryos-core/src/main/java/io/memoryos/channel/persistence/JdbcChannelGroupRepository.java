package io.memoryos.channel.persistence;

import io.memoryos.channel.ChannelGroup;
import io.memoryos.shared.TenantId;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The group chats a bot has answered in, which an administrator may give an assistant of their own (MEM-273). */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChannelGroupRepository {
    private final JdbcClient jdbc;

    public JdbcChannelGroupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a group the bot answers in, or moves its last message; a name the provider gives replaces the one held,
     * and a provider that gives none keeps it.
     */
    public void seen(TenantId tenant, UUID connection, String chatId, @Nullable String name, Instant now) {
        jdbc.sql("""
                        INSERT INTO channel_groups(tenant_id, connection_id, external_chat_id, name, first_seen_at,
                                                   last_message_at)
                        VALUES (:tenant, :connection, :chat, :name, :now, :now)
                        ON CONFLICT (connection_id, external_chat_id)
                        DO UPDATE SET name = COALESCE(EXCLUDED.name, channel_groups.name),
                                      last_message_at = GREATEST(channel_groups.last_message_at, EXCLUDED.last_message_at)
                        WHERE channel_groups.tenant_id = EXCLUDED.tenant_id
                        """)
                .param("tenant", tenant.value()).param("connection", connection).param("chat", chatId)
                .param("name", name, Types.VARCHAR).param("now", Timestamp.from(now)).update();
    }

    /** A bot's groups, the most recently asked first. */
    public List<ChannelGroup> list(TenantId tenant, UUID connection) {
        return jdbc.sql("""
                        SELECT external_chat_id, name, label, first_seen_at, last_message_at FROM channel_groups
                        WHERE tenant_id = :tenant AND connection_id = :connection
                        ORDER BY last_message_at DESC, external_chat_id
                        """)
                .param("tenant", tenant.value()).param("connection", connection)
                .query((row, ignored) -> group(row.getString(1), row.getString(2), row.getString(3),
                        row.getTimestamp(4), row.getTimestamp(5))).list();
    }

    public Optional<ChannelGroup> find(TenantId tenant, UUID connection, String chatId) {
        return jdbc.sql("""
                        SELECT external_chat_id, name, label, first_seen_at, last_message_at FROM channel_groups
                        WHERE tenant_id = :tenant AND connection_id = :connection AND external_chat_id = :chat
                        """)
                .param("tenant", tenant.value()).param("connection", connection).param("chat", chatId)
                .query((row, ignored) -> group(row.getString(1), row.getString(2), row.getString(3),
                        row.getTimestamp(4), row.getTimestamp(5))).optional();
    }

    /** Names a group, or clears the name with null; false when the bot has no such group. */
    public boolean label(TenantId tenant, UUID connection, String chatId, @Nullable String label) {
        return jdbc.sql("""
                        UPDATE channel_groups SET label = :label
                        WHERE tenant_id = :tenant AND connection_id = :connection AND external_chat_id = :chat
                        """)
                .param("label", label, Types.VARCHAR).param("tenant", tenant.value()).param("connection", connection)
                .param("chat", chatId).update() > 0;
    }

    /** Forgets a group; false when the bot has no such group. */
    public boolean forget(TenantId tenant, UUID connection, String chatId) {
        return jdbc.sql("""
                        DELETE FROM channel_groups
                        WHERE tenant_id = :tenant AND connection_id = :connection AND external_chat_id = :chat
                        """)
                .param("tenant", tenant.value()).param("connection", connection).param("chat", chatId).update() > 0;
    }

    private static ChannelGroup group(String chatId, @Nullable String name, @Nullable String label, Timestamp first,
                                      Timestamp last) {
        return new ChannelGroup(chatId, name, label, first.toInstant(), last.toInstant());
    }
}
