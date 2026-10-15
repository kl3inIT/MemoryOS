package io.memoryos.channel.persistence;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Members' accounts on platforms that name no e-mail, and the codes that link them (MEM-263). */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChannelLinkRepository {
    private final JdbcClient jdbc;

    public JdbcChannelLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Keeps the member's one code for the bot, replacing an older one. */
    public void saveCode(TenantId tenant, UUID connection, ActorId actor, byte[] codeHash, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO channel_link_codes(tenant_id, connection_id, actor_id, code_hash, expires_at)
                        VALUES (:tenant, :connection, :actor, :hash, :expires)
                        ON CONFLICT (connection_id, actor_id)
                        DO UPDATE SET code_hash = EXCLUDED.code_hash, expires_at = EXCLUDED.expires_at
                        """)
                .param("tenant", tenant.value()).param("connection", connection).param("actor", actor.value())
                .param("hash", codeHash).param("expires", Timestamp.from(expiresAt)).update();
    }

    /**
     * Uses up a live code: the code is deleted as it is read, so two messages carrying it link once. Empty when no
     * live code of the bot has this hash or its member is no longer active.
     */
    public Optional<Redeemed> redeem(UUID connection, byte[] codeHash, Instant now) {
        return jdbc.sql("""
                        WITH used AS (
                            DELETE FROM channel_link_codes
                            WHERE connection_id = :connection AND code_hash = :hash
                            RETURNING tenant_id, actor_id, expires_at)
                        SELECT used.tenant_id, used.actor_id FROM used
                        JOIN tenant_memberships m ON m.tenant_id = used.tenant_id AND m.actor_id = used.actor_id
                        WHERE used.expires_at > :now AND m.status = 'ACTIVE'
                        """)
                .param("connection", connection).param("hash", codeHash).param("now", Timestamp.from(now))
                .query((row, ignored) -> new Redeemed(new TenantId(row.getObject("tenant_id", UUID.class)),
                        new ActorId(row.getObject("actor_id", UUID.class)))).optional();
    }

    /**
     * Links the platform user to the member, within the caller's transaction. A link of the platform user to a member
     * who is no longer active is dropped, and the member's link of another account on this bot is replaced; a link
     * of the platform user to another active member is kept, and nothing is linked: that member unlinks first. The
     * database decides the last, so two codes sent at once cannot both take the account.
     *
     * @return false when the platform user is linked to another member
     */
    public boolean link(TenantId tenant, UUID connection, String externalUserId, ActorId actor, Instant now) {
        jdbc.sql("""
                        DELETE FROM channel_account_links l USING tenant_memberships m
                        WHERE l.connection_id = :connection AND l.external_user_id = :user
                          AND m.tenant_id = l.tenant_id AND m.actor_id = l.actor_id AND m.status <> 'ACTIVE'
                        """)
                .param("connection", connection).param("user", externalUserId).update();
        jdbc.sql("""
                        DELETE FROM channel_account_links
                        WHERE connection_id = :connection AND actor_id = :actor AND external_user_id <> :user
                        """)
                .param("connection", connection).param("actor", actor.value()).param("user", externalUserId).update();
        return jdbc.sql("""
                        INSERT INTO channel_account_links(tenant_id, connection_id, external_user_id, actor_id, linked_at)
                        VALUES (:tenant, :connection, :user, :actor, :now)
                        ON CONFLICT (connection_id, external_user_id) DO UPDATE SET linked_at = EXCLUDED.linked_at
                        WHERE channel_account_links.actor_id = EXCLUDED.actor_id
                        """)
                .param("tenant", tenant.value()).param("connection", connection).param("user", externalUserId)
                .param("actor", actor.value()).param("now", Timestamp.from(now)).update() == 1;
    }

    /** The active member the platform user is linked to on this bot. */
    public Optional<ActorId> linkedActor(UUID connection, String externalUserId) {
        return jdbc.sql("""
                        SELECT l.actor_id FROM channel_account_links l
                        JOIN tenant_memberships m ON m.tenant_id = l.tenant_id AND m.actor_id = l.actor_id
                        WHERE l.connection_id = :connection AND l.external_user_id = :user AND m.status = 'ACTIVE'
                        """)
                .param("connection", connection).param("user", externalUserId)
                .query((row, ignored) -> new ActorId(row.getObject("actor_id", UUID.class))).optional();
    }

    /** When the member linked an account on each bot they did. */
    public Map<UUID, Instant> linksOf(TenantId tenant, ActorId actor) {
        return jdbc.sql("""
                        SELECT connection_id, linked_at FROM channel_account_links
                        WHERE tenant_id = :tenant AND actor_id = :actor
                        """)
                .param("tenant", tenant.value()).param("actor", actor.value())
                .query((row, ignored) -> Map.entry(row.getObject("connection_id", UUID.class),
                        row.getTimestamp("linked_at").toInstant()))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Removes the member's link and code for the bot; returns how many links were removed. */
    public int unlink(TenantId tenant, UUID connection, ActorId actor) {
        int removed = jdbc.sql("DELETE FROM channel_account_links WHERE tenant_id = :tenant AND connection_id = :connection AND actor_id = :actor")
                .param("tenant", tenant.value()).param("connection", connection).param("actor", actor.value()).update();
        jdbc.sql("DELETE FROM channel_link_codes WHERE tenant_id = :tenant AND connection_id = :connection AND actor_id = :actor")
                .param("tenant", tenant.value()).param("connection", connection).param("actor", actor.value()).update();
        return removed;
    }

    /** How many messages of the sender since {@code since} were settled with {@code reason}. */
    public int settledWith(UUID connection, String externalUserId, String reason, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM channel_messages
                        WHERE connection_id = :connection AND external_user_id = :user AND reason = :reason
                          AND received_at >= :since
                        """)
                .param("connection", connection).param("user", externalUserId).param("reason", reason)
                .param("since", Timestamp.from(since)).query(Integer.class).single();
    }

    /** How many messages of the sender in one chat since {@code since} were settled with {@code reason}. */
    public int settledWithIn(UUID connection, String externalUserId, String externalChatId, String reason, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM channel_messages
                        WHERE connection_id = :connection AND external_user_id = :user AND external_chat_id = :chat
                          AND reason = :reason AND received_at >= :since
                        """)
                .param("connection", connection).param("user", externalUserId).param("chat", externalChatId)
                .param("reason", reason).param("since", Timestamp.from(since)).query(Integer.class).single();
    }

    public record Redeemed(TenantId tenant, ActorId actor) {}
}
