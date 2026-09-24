package io.memoryos.library.persistence;

import io.memoryos.library.LibraryArchive;
import io.memoryos.library.LibraryArchiveItem;
import io.memoryos.library.LibraryArchiveStatus;
import io.memoryos.library.LibraryFile;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.StoredObjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * ZIP archives of a library selection (MEM-152). A request is claimed by one Worker with a lease, exactly as a
 * usage report is, and a finished archive is released again once it expires.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcLibraryArchiveRepository {
    private final JdbcClient jdbc;

    public JdbcLibraryArchiveRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Claim(UUID id, UUID tenant, UUID owner, List<LibraryArchiveItem> requested, int attempts) {}

    public record Expired(TenantId tenant, UUID id, StoredObjectId object, ObjectKey key, UUID token) {}

    private static final String COLUMNS =
            "a.id, a.status, a.file_count, a.size_bytes, a.skipped::text AS skipped, a.failure, a.created_at, a.expires_at";

    public LibraryArchive insert(TenantId tenant, ActorId owner, UUID id, List<LibraryArchiveItem> requested, long requestedBytes,
                          Duration lifetime) {
        jdbc.sql("""
                INSERT INTO chat_library_archive(id, tenant_id, owner_actor_id, requested, file_count, requested_bytes, expires_at)
                VALUES (:id, :tenant, :owner, CAST(:requested AS jsonb), :count, :bytes,
                        CURRENT_TIMESTAMP + make_interval(secs => :lifetime))
                """).param("id", id).param("tenant", tenant.value()).param("owner", owner.value())
                .param("requested", json(requested)).param("count", requested.size()).param("bytes", requestedBytes)
                .param("lifetime", lifetime.toSeconds()).update();
        return find(tenant, owner, id).orElseThrow();
    }

    public Optional<LibraryArchive> find(TenantId tenant, ActorId owner, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM chat_library_archive a
                WHERE a.tenant_id = :tenant AND a.owner_actor_id = :owner AND a.id = :id
                """).param("tenant", tenant.value()).param("owner", owner.value()).param("id", id)
                .query((row, ignored) -> archive(row)).optional();
    }

    /** The owner's archives, newest first; the page offers each one for as long as it lives. */
    public List<LibraryArchive> list(TenantId tenant, ActorId owner, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM chat_library_archive a
                WHERE a.tenant_id = :tenant AND a.owner_actor_id = :owner AND a.status <> 'FAILED'
                  AND (a.status <> 'READY' OR a.expires_at > CURRENT_TIMESTAMP)
                ORDER BY a.created_at DESC, a.id LIMIT :limit
                """).param("tenant", tenant.value()).param("owner", owner.value()).param("limit", limit)
                .query((row, ignored) -> archive(row)).list();
    }

    /** How many archives the owner has waiting or being packed, which bounds what one person may queue. */
    public int active(TenantId tenant, ActorId owner) {
        return jdbc.sql("""
                SELECT count(*) FROM chat_library_archive
                WHERE tenant_id = :tenant AND owner_actor_id = :owner AND status IN ('PENDING', 'RUNNING')
                """).param("tenant", tenant.value()).param("owner", owner.value()).query(Integer.class).single();
    }

    /** The bytes of a READY archive its owner may download. */
    public Optional<ObjectKey> content(TenantId tenant, ActorId owner, UUID id) {
        return jdbc.sql("""
                SELECT object_key FROM chat_library_archive
                WHERE tenant_id = :tenant AND owner_actor_id = :owner AND id = :id
                  AND status = 'READY' AND expires_at > CURRENT_TIMESTAMP
                """).param("tenant", tenant.value()).param("owner", owner.value()).param("id", id)
                .query((row, ignored) -> new ObjectKey(row.getString("object_key"))).optional();
    }

    public Optional<Claim> claim(Duration lease, int maxAttempts) {
        return jdbc.sql("""
                UPDATE chat_library_archive a SET status = 'RUNNING', attempts = a.attempts + 1,
                       lease_until = CURRENT_TIMESTAMP + make_interval(secs => :lease)
                WHERE a.id = (
                    SELECT id FROM chat_library_archive
                    WHERE (status = 'PENDING' OR (status = 'RUNNING' AND lease_until < CURRENT_TIMESTAMP))
                      AND attempts < :max
                    ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING a.id, a.tenant_id, a.owner_actor_id, a.requested::text AS requested, a.attempts
                """).param("lease", lease.toSeconds()).param("max", maxAttempts)
                .query((row, ignored) -> new Claim(row.getObject("id", UUID.class), row.getObject("tenant_id", UUID.class),
                        row.getObject("owner_actor_id", UUID.class), requested(row.getString("requested")), row.getInt("attempts")))
                .optional();
    }

    /** Requests whose last permitted attempt ran out of lease without finishing. */
    public int failAbandoned(int maxAttempts) {
        return jdbc.sql("""
                UPDATE chat_library_archive SET status = 'FAILED', lease_until = NULL, finished_at = CURRENT_TIMESTAMP,
                       failure = 'The archive could not be packed.'
                WHERE status = 'RUNNING' AND lease_until < CURRENT_TIMESTAMP AND attempts >= :max
                """).param("max", maxAttempts).update();
    }

    /** False when the lease was lost to another Worker, which then owns the outcome. */
    public boolean markReady(UUID tenant, UUID id, int attempt, StoredObjectId object, ObjectKey key, long size,
                             List<String> skipped) {
        return jdbc.sql("""
                UPDATE chat_library_archive SET status = 'READY', lease_until = NULL, finished_at = CURRENT_TIMESTAMP,
                       failure = NULL, stored_object_id = :object, object_key = :key, size_bytes = :size,
                       skipped = CAST(:skipped AS jsonb)
                WHERE tenant_id = :tenant AND id = :id AND status = 'RUNNING' AND attempts = :attempt
                """).param("tenant", tenant).param("id", id).param("attempt", attempt).param("object", object.value())
                .param("key", key.value()).param("size", size).param("skipped", jsonStrings(skipped)).update() == 1;
    }

    public void markFailed(UUID tenant, UUID id, int attempt, int maxAttempts, String failure) {
        jdbc.sql("""
                UPDATE chat_library_archive SET
                       status = CASE WHEN attempts >= :max THEN 'FAILED' ELSE 'PENDING' END,
                       finished_at = CASE WHEN attempts >= :max THEN CURRENT_TIMESTAMP END,
                       failure = CASE WHEN attempts >= :max THEN :failure END,
                       lease_until = NULL
                WHERE tenant_id = :tenant AND id = :id AND status = 'RUNNING' AND attempts = :attempt
                """).param("tenant", tenant).param("id", id).param("attempt", attempt).param("max", maxAttempts)
                .param("failure", failure).update();
    }

    /** Claims expired archives for the byte sweep, as the artifact cleanup claims deleted artifacts. */
    public List<Expired> claimExpired(int limit, Duration lease) {
        var token = UUID.randomUUID();
        return jdbc.sql("""
                UPDATE chat_library_archive a SET cleanup_token = :token,
                       cleanup_until = CURRENT_TIMESTAMP + make_interval(secs => :lease)
                WHERE a.id IN (
                    SELECT id FROM chat_library_archive
                    WHERE status = 'READY' AND expires_at < CURRENT_TIMESTAMP
                      AND (cleanup_until IS NULL OR cleanup_until < CURRENT_TIMESTAMP)
                    ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED)
                RETURNING a.tenant_id, a.id, a.stored_object_id, a.object_key
                """).param("token", token).param("lease", lease.toSeconds()).param("limit", limit)
                .query((row, ignored) -> new Expired(new TenantId(row.getObject("tenant_id", UUID.class)),
                        row.getObject("id", UUID.class), new StoredObjectId(row.getObject("stored_object_id", UUID.class)),
                        new ObjectKey(row.getString("object_key")), token))
                .list();
    }

    /** Removes the row under the claim this sweep holds; false when the claim lapsed. */
    public boolean remove(Expired expired) {
        return jdbc.sql("""
                DELETE FROM chat_library_archive
                WHERE tenant_id = :tenant AND id = :id AND cleanup_token = :token
                """).param("tenant", expired.tenant().value()).param("id", expired.id())
                .param("token", expired.token()).update() == 1;
    }

    private static LibraryArchive archive(ResultSet row) throws SQLException {
        Timestamp expires = row.getTimestamp("expires_at");
        Long size = row.getObject("size_bytes", Long.class);
        return new LibraryArchive(row.getObject("id", UUID.class), LibraryArchiveStatus.valueOf(row.getString("status")),
                row.getInt("file_count"), size, strings(row.getString("skipped")), row.getString("failure"),
                row.getTimestamp("created_at").toInstant(), expires == null ? null : expires.toInstant());
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String json(List<LibraryArchiveItem> requested) {
        var array = JSON.createArrayNode();
        requested.forEach(file -> array.addObject().put("source", file.source().name()).put("id", file.id().toString()));
        return array.toString();
    }

    private static List<LibraryArchiveItem> requested(String json) {
        var files = new java.util.ArrayList<LibraryArchiveItem>();
        JSON.readTree(json).forEach(file -> files.add(new LibraryArchiveItem(
                LibraryFile.Source.valueOf(file.path("source").asString()),
                UUID.fromString(file.path("id").asString()))));
        return List.copyOf(files);
    }

    /** Names only; a skipped file is reported to its owner, who already knows its name. */
    private static String jsonStrings(List<String> values) {
        var array = JSON.createArrayNode();
        values.forEach(array::add);
        return array.toString();
    }

    private static List<String> strings(String json) {
        var values = new java.util.ArrayList<String>();
        JSON.readTree(json).forEach(value -> values.add(value.asString()));
        return List.copyOf(values);
    }
}
