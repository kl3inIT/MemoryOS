package io.memoryos.chat.export.persistence;

import io.memoryos.chat.ChatExport;
import io.memoryos.chat.ChatExportStatus;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.LeasedJob;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.StoredObjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Exports of one person's own conversations and files (MEM-153). A request is claimed by one Worker under a
 * lease and a finished export is released when it expires, exactly as a library archive is (MEM-152); what an
 * export holds is decided while it is packed, so the row records no selection.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChatExportRepository {
    private final JdbcClient jdbc;

    public JdbcChatExportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Claim(UUID id, UUID tenant, UUID owner, int attempts) implements LeasedJob.Claim {}

    public record Expired(TenantId tenant, UUID id, StoredObjectId object, ObjectKey key, UUID token) {}

    private static final String COLUMNS = "e.id, e.status, e.session_count, e.file_count, e.skipped::text AS skipped, "
            + "e.size_bytes, e.failure, e.created_at, e.expires_at";

    /**
     * Records the request. The partial unique index allows one waiting export per person, so a second click
     * while one is being packed is a conflict rather than a second read of everything they own.
     */
    public Optional<ChatExport> insert(TenantId tenant, ActorId owner, UUID id, Duration lifetime) {
        try {
            jdbc.sql("""
                    INSERT INTO chat_export(id, tenant_id, owner_actor_id, expires_at)
                    VALUES (:id, :tenant, :owner, CURRENT_TIMESTAMP + make_interval(secs => :lifetime))
                    """).param("id", id).param("tenant", tenant.value()).param("owner", owner.value())
                    .param("lifetime", lifetime.toSeconds()).update();
        } catch (DuplicateKeyException alreadyQueued) {
            return Optional.empty();
        }
        return find(tenant, owner, id);
    }

    public Optional<ChatExport> find(TenantId tenant, ActorId owner, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM chat_export e
                WHERE e.tenant_id = :tenant AND e.owner_actor_id = :owner AND e.id = :id
                """).param("tenant", tenant.value()).param("owner", owner.value()).param("id", id)
                .query((row, ignored) -> export(row)).optional();
    }

    /** The owner's exports worth offering: what is being packed, and what is ready and still alive. */
    public List<ChatExport> list(TenantId tenant, ActorId owner, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM chat_export e
                WHERE e.tenant_id = :tenant AND e.owner_actor_id = :owner AND e.status <> 'FAILED'
                  AND (e.status <> 'READY' OR e.expires_at > CURRENT_TIMESTAMP)
                ORDER BY e.created_at DESC, e.id LIMIT :limit
                """).param("tenant", tenant.value()).param("owner", owner.value()).param("limit", limit)
                .query((row, ignored) -> export(row)).list();
    }

    /** The bytes of a READY export its owner may download. */
    public Optional<ObjectKey> content(TenantId tenant, ActorId owner, UUID id) {
        return jdbc.sql("""
                SELECT object_key FROM chat_export
                WHERE tenant_id = :tenant AND owner_actor_id = :owner AND id = :id
                  AND status = 'READY' AND expires_at > CURRENT_TIMESTAMP
                """).param("tenant", tenant.value()).param("owner", owner.value()).param("id", id)
                .query((row, ignored) -> new ObjectKey(row.getString("object_key"))).optional();
    }

    public Optional<Claim> claim(Duration lease, int maxAttempts) {
        return jdbc.sql("""
                UPDATE chat_export e SET status = 'RUNNING', attempts = e.attempts + 1,
                       lease_until = CURRENT_TIMESTAMP + make_interval(secs => :lease)
                WHERE e.id = (
                    SELECT id FROM chat_export
                    WHERE (status = 'PENDING' OR (status = 'RUNNING' AND lease_until < CURRENT_TIMESTAMP))
                      AND attempts < :max
                    ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING e.id, e.tenant_id, e.owner_actor_id, e.attempts
                """).param("lease", lease.toSeconds()).param("max", maxAttempts)
                .query((row, ignored) -> new Claim(row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class), row.getObject("owner_actor_id", UUID.class),
                        row.getInt("attempts")))
                .optional();
    }

    /** Requests whose last permitted attempt ran out of lease without finishing. */
    public int failAbandoned(int maxAttempts) {
        return jdbc.sql("""
                UPDATE chat_export SET status = 'FAILED', lease_until = NULL, finished_at = CURRENT_TIMESTAMP,
                       failure = 'The export could not be packed.'
                WHERE status = 'RUNNING' AND lease_until < CURRENT_TIMESTAMP AND attempts >= :max
                """).param("max", maxAttempts).update();
    }

    /** False when the lease was lost to another Worker, which then owns the outcome. */
    public boolean markReady(UUID tenant, UUID id, int attempt, StoredObjectId object, ObjectKey key, long size,
                             int sessions, int files, List<String> skipped) {
        return jdbc.sql("""
                UPDATE chat_export SET status = 'READY', lease_until = NULL, finished_at = CURRENT_TIMESTAMP,
                       failure = NULL, stored_object_id = :object, object_key = :key, size_bytes = :size,
                       session_count = :sessions, file_count = :files, skipped = CAST(:skipped AS jsonb)
                WHERE tenant_id = :tenant AND id = :id AND status = 'RUNNING' AND attempts = :attempt
                """).param("tenant", tenant).param("id", id).param("attempt", attempt).param("object", object.value())
                .param("key", key.value()).param("size", size).param("sessions", sessions).param("files", files)
                .param("skipped", jsonStrings(skipped)).update() == 1;
    }

    public void markFailed(UUID tenant, UUID id, int attempt, int maxAttempts, String failure) {
        jdbc.sql("""
                UPDATE chat_export SET
                       status = CASE WHEN attempts >= :max THEN 'FAILED' ELSE 'PENDING' END,
                       finished_at = CASE WHEN attempts >= :max THEN CURRENT_TIMESTAMP END,
                       failure = CASE WHEN attempts >= :max THEN :failure END,
                       lease_until = NULL
                WHERE tenant_id = :tenant AND id = :id AND status = 'RUNNING' AND attempts = :attempt
                """).param("tenant", tenant).param("id", id).param("attempt", attempt).param("max", maxAttempts)
                .param("failure", failure).update();
    }

    /** Claims expired exports for the byte sweep, as the library archive sweep claims its own. */
    public List<Expired> claimExpired(int limit, Duration lease) {
        var token = UUID.randomUUID();
        return jdbc.sql("""
                UPDATE chat_export e SET cleanup_token = :token,
                       cleanup_until = CURRENT_TIMESTAMP + make_interval(secs => :lease)
                WHERE e.id IN (
                    SELECT id FROM chat_export
                    WHERE status = 'READY' AND expires_at < CURRENT_TIMESTAMP
                      AND (cleanup_until IS NULL OR cleanup_until < CURRENT_TIMESTAMP)
                    ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                RETURNING e.tenant_id, e.id, e.stored_object_id, e.object_key
                """).param("token", token).param("lease", lease.toSeconds()).param("limit", limit)
                .query((row, ignored) -> new Expired(new TenantId(row.getObject("tenant_id", UUID.class)),
                        row.getObject("id", UUID.class),
                        new StoredObjectId(row.getObject("stored_object_id", UUID.class)),
                        new ObjectKey(row.getString("object_key")), token))
                .list();
    }

    /** Removes the row under the claim this sweep holds; false when the claim lapsed. */
    public boolean remove(Expired expired) {
        return jdbc.sql("""
                DELETE FROM chat_export WHERE tenant_id = :tenant AND id = :id AND cleanup_token = :token
                """).param("tenant", expired.tenant().value()).param("id", expired.id())
                .param("token", expired.token()).update() == 1;
    }

    private static ChatExport export(ResultSet row) throws SQLException {
        Timestamp expires = row.getTimestamp("expires_at");
        return new ChatExport(row.getObject("id", UUID.class), ChatExportStatus.valueOf(row.getString("status")),
                row.getObject("session_count", Integer.class), row.getObject("file_count", Integer.class),
                strings(row.getString("skipped")), row.getObject("size_bytes", Long.class), row.getString("failure"),
                row.getTimestamp("created_at").toInstant(), expires == null ? null : expires.toInstant());
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Names only: what an export left out is reported to the person who owns it. */
    private static String jsonStrings(List<String> values) {
        var array = JSON.createArrayNode();
        values.forEach(array::add);
        return array.toString();
    }

    private static List<String> strings(String json) {
        var values = new ArrayList<String>();
        JSON.readTree(json).forEach(value -> values.add(value.asString()));
        return List.copyOf(values);
    }
}
