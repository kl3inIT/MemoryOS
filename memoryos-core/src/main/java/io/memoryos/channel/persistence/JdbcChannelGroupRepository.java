package io.memoryos.channel.persistence;

import io.memoryos.channel.ChannelGroup;
import io.memoryos.channel.ChannelGroupPresence;
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

/**
 * The group chats a bot is known in, which an administrator may give an assistant of their own (MEM-273): those it has
 * answered in, and on Lark those it is in before anyone asks there.
 */
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

    /**
     * Records a group the provider says the bot is in, before anyone asks there; a name the provider gives replaces the
     * one held, and a group already known keeps its last message.
     */
    public void present(TenantId tenant, UUID connection, String chatId, @Nullable String name, Instant now) {
        jdbc.sql("""
                        INSERT INTO channel_groups(tenant_id, connection_id, external_chat_id, name, first_seen_at)
                        VALUES (:tenant, :connection, :chat, :name, :now)
                        ON CONFLICT (connection_id, external_chat_id)
                        DO UPDATE SET name = COALESCE(EXCLUDED.name, channel_groups.name)
                        WHERE channel_groups.tenant_id = EXCLUDED.tenant_id
                        """)
                .param("tenant", tenant.value()).param("connection", connection).param("chat", chatId)
                .param("name", name, Types.VARCHAR).param("now", Timestamp.from(now)).update();
    }

    /**
     * Makes a bot's groups the provider's whole list, read from {@code readAt}: records those it lists and removes the
     * others, which the bot has left. A group joined or asked in since {@code readAt} stays, as the list may predate it.
     * Returns the groups removed.
     */
    public List<String> replace(TenantId tenant, UUID connection, List<ChannelGroupPresence> listed, Instant readAt,
                                Instant now) {
        for (var group : listed) present(tenant, connection, group.chatId(), group.name(), now);
        // NOT IN over an empty list is invalid SQL; no id matches the empty string.
        var kept = listed.isEmpty() ? List.of("") : listed.stream().map(ChannelGroupPresence::chatId).toList();
        return jdbc.sql("""
                        DELETE FROM channel_groups
                        WHERE tenant_id = :tenant AND connection_id = :connection AND external_chat_id NOT IN (:kept)
                          AND first_seen_at < :readAt AND (last_message_at IS NULL OR last_message_at < :readAt)
                        RETURNING external_chat_id
                        """)
                .param("tenant", tenant.value()).param("connection", connection).param("kept", kept)
                .param("readAt", Timestamp.from(readAt)).query(String.class).list();
    }

    /** A bot's groups, the most recently asked first and those nobody has asked in last. */
    public List<ChannelGroup> list(TenantId tenant, UUID connection) {
        return jdbc.sql("""
                        SELECT external_chat_id, name, label, first_seen_at, last_message_at, allowed_at FROM channel_groups
                        WHERE tenant_id = :tenant AND connection_id = :connection
                        ORDER BY last_message_at DESC NULLS LAST, COALESCE(label, name), external_chat_id
                        """)
                .param("tenant", tenant.value()).param("connection", connection)
                .query((row, ignored) -> group(row.getString(1), row.getString(2), row.getString(3),
                        row.getTimestamp(4), row.getTimestamp(5), row.getTimestamp(6))).list();
    }

    public Optional<ChannelGroup> find(TenantId tenant, UUID connection, String chatId) {
        return jdbc.sql("""
                        SELECT external_chat_id, name, label, first_seen_at, last_message_at, allowed_at FROM channel_groups
                        WHERE tenant_id = :tenant AND connection_id = :connection AND external_chat_id = :chat
                        """)
                .param("tenant", tenant.value()).param("connection", connection).param("chat", chatId)
                .query((row, ignored) -> group(row.getString(1), row.getString(2), row.getString(3),
                        row.getTimestamp(4), row.getTimestamp(5), row.getTimestamp(6))).optional();
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

    /** Allows the bot to answer in a group, or withdraws it with null; false when the bot has no such group. */
    public boolean allow(TenantId tenant, UUID connection, String chatId, @Nullable Instant allowedAt) {
        return jdbc.sql("""
                        UPDATE channel_groups SET allowed_at = :allowed
                        WHERE tenant_id = :tenant AND connection_id = :connection AND external_chat_id = :chat
                        """)
                .param("allowed", allowedAt == null ? null : Timestamp.from(allowedAt), Types.TIMESTAMP)
                .param("tenant", tenant.value()).param("connection", connection).param("chat", chatId).update() > 0;
    }

    /** Whether an administrator allowed the bot to answer in a group it knows. */
    public boolean allowed(TenantId tenant, UUID connection, String chatId) {
        return jdbc.sql("""
                        SELECT allowed_at IS NOT NULL FROM channel_groups
                        WHERE tenant_id = :tenant AND connection_id = :connection AND external_chat_id = :chat
                        """)
                .param("tenant", tenant.value()).param("connection", connection).param("chat", chatId)
                .query(Boolean.class).optional().orElse(false);
    }

    private static ChannelGroup group(String chatId, @Nullable String name, @Nullable String label, Timestamp first,
                                      @Nullable Timestamp last, @Nullable Timestamp allowed) {
        return new ChannelGroup(chatId, name, label, first.toInstant(), last == null ? null : last.toInstant(),
                allowed == null ? null : allowed.toInstant());
    }
}
