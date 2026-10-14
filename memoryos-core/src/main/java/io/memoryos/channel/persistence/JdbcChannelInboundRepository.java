package io.memoryos.channel.persistence;

import io.memoryos.channel.ChannelMessage;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** What a bot received, who its senders are and which conversation a message continues (MEM-246). */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChannelInboundRepository {
    private final JdbcClient jdbc;

    public JdbcChannelInboundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Takes the message for this process; false when it was already received, here or by another process. */
    public boolean claim(TenantId tenant, ChannelMessage message) {
        return jdbc.sql("""
                        INSERT INTO channel_messages(tenant_id, connection_id, external_message_id, external_chat_id,
                                                     external_user_id)
                        VALUES (:tenant, :connection, :message, :chat, :sender)
                        ON CONFLICT (connection_id, external_message_id) DO NOTHING
                        """)
                .param("tenant", tenant.value()).param("connection", message.connectionId())
                .param("message", message.messageId()).param("chat", message.chatId()).param("sender", message.senderId())
                .update() == 1;
    }

    /** How the message ended: {@code ANSWERED}, {@code REFUSED} or {@code FAILED}, with what a reply to it needs. */
    /** How many messages {@code senderId} sent the bot since {@code since}, this one included once claimed. */
    public int receivedFrom(UUID connection, String senderId, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM channel_messages
                        WHERE connection_id = :connection AND external_user_id = :sender AND received_at >= :since
                        """)
                .param("connection", connection).param("sender", senderId).param("since", Timestamp.from(since))
                .query(Integer.class).single();
    }

    public void settle(UUID connection, String messageId, String outcome, @Nullable String reason, @Nullable UUID session,
                       @Nullable UUID assistantMessage, @Nullable String replyMessageId) {
        jdbc.sql("""
                        UPDATE channel_messages SET outcome = :outcome, reason = :reason, session_id = :session,
                               assistant_message_id = :assistant, reply_external_message_id = :reply
                        WHERE connection_id = :connection AND external_message_id = :message
                        """)
                .param("outcome", outcome).param("reason", reason, Types.VARCHAR).param("session", session, Types.OTHER)
                .param("assistant", assistantMessage, Types.OTHER).param("reply", replyMessageId, Types.VARCHAR)
                .param("connection", connection).param("message", messageId).update();
    }

    /** The conversation of the message a reply names: the question itself or the bot's answer to it. */
    public Optional<UUID> sessionOf(UUID connection, String repliedMessageId) {
        return jdbc.sql("""
                        SELECT session_id FROM channel_messages
                        WHERE connection_id = :connection AND session_id IS NOT NULL
                          AND (external_message_id = :message OR reply_external_message_id = :message)
                        ORDER BY received_at DESC LIMIT 1
                        """)
                .param("connection", connection).param("message", repliedMessageId).query(UUID.class).optional();
    }

    public Optional<Identity> identity(UUID connection, String senderId) {
        return jdbc.sql("""
                        SELECT actor_id, resolved_at FROM channel_identities
                        WHERE connection_id = :connection AND external_user_id = :sender
                        """)
                .param("connection", connection).param("sender", senderId)
                .query((row, ignored) -> {
                    UUID actor = row.getObject("actor_id", UUID.class);
                    return new Identity(actor == null ? null : new ActorId(actor), row.getTimestamp("resolved_at").toInstant());
                }).optional();
    }

    public void remember(TenantId tenant, UUID connection, String senderId, @Nullable ActorId actor, Instant now) {
        jdbc.sql("""
                        INSERT INTO channel_identities(tenant_id, connection_id, external_user_id, actor_id, resolved_at)
                        VALUES (:tenant, :connection, :sender, :actor, :now)
                        ON CONFLICT (connection_id, external_user_id)
                        DO UPDATE SET actor_id = EXCLUDED.actor_id, resolved_at = EXCLUDED.resolved_at
                        """)
                .param("tenant", tenant.value()).param("connection", connection).param("sender", senderId)
                .param("actor", actor == null ? null : actor.value(), Types.OTHER).param("now", Timestamp.from(now)).update();
    }

    public Optional<Conversation> conversation(UUID connection, String chatId, String senderId) {
        return jdbc.sql("""
                        SELECT session_id, last_message_at FROM channel_conversations
                        WHERE connection_id = :connection AND external_chat_id = :chat AND external_user_id = :sender
                        """)
                .param("connection", connection).param("chat", chatId).param("sender", senderId)
                .query((row, ignored) -> new Conversation(row.getObject("session_id", UUID.class),
                        row.getTimestamp("last_message_at").toInstant())).optional();
    }

    public void keep(TenantId tenant, UUID connection, String chatId, String senderId, UUID session, Instant now) {
        jdbc.sql("""
                        INSERT INTO channel_conversations(tenant_id, connection_id, external_chat_id, external_user_id,
                                                          session_id, last_message_at)
                        VALUES (:tenant, :connection, :chat, :sender, :session, :now)
                        ON CONFLICT (connection_id, external_chat_id, external_user_id)
                        DO UPDATE SET session_id = EXCLUDED.session_id, last_message_at = EXCLUDED.last_message_at
                        """)
                .param("tenant", tenant.value()).param("connection", connection).param("chat", chatId)
                .param("sender", senderId).param("session", session).param("now", Timestamp.from(now)).update();
    }

    public void forget(UUID connection, String chatId, String senderId) {
        jdbc.sql("""
                        DELETE FROM channel_conversations
                        WHERE connection_id = :connection AND external_chat_id = :chat AND external_user_id = :sender
                        """)
                .param("connection", connection).param("chat", chatId).param("sender", senderId).update();
    }

    /** Deletes at most {@code limit} messages received before {@code before}; returns how many. */
    public int deleteMessagesBefore(Instant before, int limit) {
        return jdbc.sql("""
                        DELETE FROM channel_messages WHERE (connection_id, external_message_id) IN (
                            SELECT connection_id, external_message_id FROM channel_messages
                            WHERE received_at < :before ORDER BY received_at LIMIT :limit)
                        """)
                .param("before", Timestamp.from(before)).param("limit", limit).update();
    }

    /** {@code actor} is null for a member of the organisation without an account here. */
    public record Identity(@Nullable ActorId actor, Instant resolvedAt) {}

    public record Conversation(UUID session, Instant lastMessageAt) {}
}
