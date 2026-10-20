package io.memoryos.channel.persistence;

import io.memoryos.channel.ChannelPairing;
import io.memoryos.shared.TenantId;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Which channel process holds each bot (MEM-283), on the database's clock so that no process's clock decides, and
 * what the holder writes of it: its state and the code a bot being linked shows. Every write of the holder carries the
 * epoch it took the bot at, so a process that lost the bot cannot overwrite its successor.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChannelLeaseRepository {
    private final JdbcClient jdbc;

    public JdbcChannelLeaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes or renews the lease of a bot for {@code holder}: a bot nobody holds, whose lease ended, or that the holder
     * already holds. A change of hands starts a new epoch. Empty when another process holds it.
     */
    public OptionalLong hold(UUID connection, String holder, String address, Duration lease) {
        return jdbc.sql("""
                        INSERT INTO channel_connection_state(connection_id, heartbeat_at, holder, holder_address,
                                                             holder_since, lease_until, lease_epoch)
                        VALUES (:connection, now(), :holder, :address, now(), now() + :lease * INTERVAL '1 millisecond', 1)
                        ON CONFLICT (connection_id) DO UPDATE SET
                            lease_epoch = CASE WHEN channel_connection_state.holder IS NOT DISTINCT FROM EXCLUDED.holder
                                AND channel_connection_state.lease_until >= now()
                                THEN channel_connection_state.lease_epoch ELSE channel_connection_state.lease_epoch + 1 END,
                            holder_since = CASE WHEN channel_connection_state.holder IS NOT DISTINCT FROM EXCLUDED.holder
                                AND channel_connection_state.lease_until >= now()
                                THEN channel_connection_state.holder_since ELSE now() END,
                            holder = EXCLUDED.holder, holder_address = EXCLUDED.holder_address,
                            lease_until = EXCLUDED.lease_until
                        WHERE channel_connection_state.holder IS NULL OR channel_connection_state.holder = EXCLUDED.holder
                            OR channel_connection_state.lease_until < now()
                        RETURNING lease_epoch
                        """)
                .param("connection", connection).param("holder", holder).param("address", address)
                .param("lease", lease.toMillis())
                .query(Long.class).optional().map(OptionalLong::of).orElse(OptionalLong.empty());
    }

    /** Renews every unexpired lease {@code holder} has among {@code connections}; returns the bots it still holds. */
    public Set<UUID> renew(String holder, Collection<UUID> connections, Duration lease) {
        if (connections.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.sql("""
                        UPDATE channel_connection_state SET lease_until = now() + :lease * INTERVAL '1 millisecond'
                        WHERE holder = :holder AND lease_until >= now() AND connection_id IN (:connections)
                        RETURNING connection_id
                        """)
                .param("holder", holder).param("lease", lease.toMillis()).param("connections", connections)
                .query(UUID.class).list());
    }

    /** Gives up every bot {@code holder} holds, so another process takes them at its next pass. */
    public void release(String holder) {
        jdbc.sql("""
                        UPDATE channel_connection_state SET holder = NULL, holder_address = NULL, holder_since = NULL,
                            lease_until = NULL, opened_at = NULL
                        WHERE holder = :holder
                        """)
                .param("holder", holder).update();
    }

    /** Gives up one bot, closed because it was switched off or removed; nothing when another process holds it. */
    public void release(UUID connection, String holder, long epoch) {
        jdbc.sql("""
                        UPDATE channel_connection_state SET holder = NULL, holder_address = NULL, holder_since = NULL,
                            lease_until = NULL, opened_at = NULL
                        WHERE connection_id = :connection AND holder = :holder AND lease_epoch = :epoch
                        """)
                .param("connection", connection).param("holder", holder).param("epoch", epoch).update();
    }

    /** Where a live holder other than {@code self} serves the bot's webhook; empty when none does. */
    public Optional<String> liveAddress(UUID connection, String self) {
        return jdbc.sql("""
                        SELECT holder_address FROM channel_connection_state
                        WHERE connection_id = :connection AND holder IS NOT NULL AND holder <> :self
                            AND lease_until >= now() AND holder_address <> ''
                        """)
                .param("connection", connection).param("self", self).query(String.class).optional();
    }

    /**
     * What the holder saw of a bot's connection at this pass; nothing when it no longer holds the bot at this epoch.
     * Returns whether it was written.
     */
    public boolean recordState(UUID connection, String holder, long epoch, @Nullable Instant openedAt,
                               @Nullable String failureCode, @Nullable Instant failedAt, @Nullable Instant lastMessageAt,
                               Instant now) {
        return jdbc.sql("""
                        UPDATE channel_connection_state SET opened_at = :opened, last_failure_code = :failure,
                            last_failure_at = :failed, last_message_at = :message, heartbeat_at = :now
                        WHERE connection_id = :connection AND holder = :holder AND lease_epoch = :epoch
                        """)
                .param("connection", connection).param("holder", holder).param("epoch", epoch)
                .param("opened", timestamp(openedAt), Types.TIMESTAMP)
                .param("failure", failureCode, Types.VARCHAR).param("failed", timestamp(failedAt), Types.TIMESTAMP)
                .param("message", timestamp(lastMessageAt), Types.TIMESTAMP)
                .param("now", Timestamp.from(now)).update() == 1;
    }

    /** The code a bot being linked shows now, replacing the last; nothing when the holder lost the bot. */
    public void recordPairing(UUID connection, String holder, long epoch, String code, Instant expiresAt, Instant now) {
        jdbc.sql("""
                        INSERT INTO channel_pairings(connection_id, code, expires_at, updated_at)
                        SELECT :connection, :code, :expires, :now FROM channel_connection_state
                        WHERE connection_id = :connection AND holder = :holder AND lease_epoch = :epoch
                        ON CONFLICT (connection_id) DO UPDATE SET code = EXCLUDED.code, expires_at = EXCLUDED.expires_at,
                            updated_at = EXCLUDED.updated_at
                        """)
                .param("connection", connection).param("holder", holder).param("epoch", epoch).param("code", code)
                .param("expires", Timestamp.from(expiresAt)).param("now", Timestamp.from(now)).update();
    }

    /** No code is shown for the bot any more; nothing when the holder lost it. */
    public void clearPairing(UUID connection, String holder, long epoch) {
        jdbc.sql("""
                        DELETE FROM channel_pairings p USING channel_connection_state s
                        WHERE p.connection_id = :connection AND s.connection_id = p.connection_id
                            AND s.holder = :holder AND s.lease_epoch = :epoch
                        """)
                .param("connection", connection).param("holder", holder).param("epoch", epoch).update();
    }

    /** The code one of the Tenant's bots shows now, unless it expired. */
    public Optional<ChannelPairing> pairing(TenantId tenant, UUID connection, Instant now) {
        return jdbc.sql("""
                        SELECT p.code, p.expires_at FROM channel_pairings p
                        JOIN channel_connections c ON c.id = p.connection_id
                        WHERE c.tenant_id = :tenant AND p.connection_id = :connection AND p.expires_at > :now
                        """)
                .param("tenant", tenant.value()).param("connection", connection).param("now", Timestamp.from(now))
                .query((row, ignored) -> new ChannelPairing(row.getString("code"), row.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    private static @Nullable Timestamp timestamp(@Nullable Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
