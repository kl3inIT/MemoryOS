package io.memoryos.library.persistence;

import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.library.UserFile;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcUserFileRepository {
    private final JdbcClient jdbc;

    public JdbcUserFileRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** A file row; {@code uploadId} names the browser upload it came from and is absent for a server-written copy. */
    public record Row(UserFile file, @Nullable ObjectUploadId uploadId, ContentSha256 checksum, String declaredMediaType) {}

    public Optional<Row> request(TenantId tenant, ActorId actor, UUID request) {
        return jdbc.sql("SELECT * FROM chat_user_file WHERE tenant_id=:tenant AND owner_actor_id=:actor AND request_id=:request")
                .param("tenant", tenant.value()).param("actor", actor.value()).param("request", request)
                .query((row, ignored) -> map(row)).optional();
    }

    public Optional<Row> owned(TenantId tenant, ActorId actor, UUID id, boolean lock) {
        return jdbc.sql("SELECT * FROM chat_user_file WHERE tenant_id=:tenant AND owner_actor_id=:actor AND id=:id"
                        + (lock ? " FOR UPDATE" : ""))
                .param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .query((row, ignored) -> map(row)).optional();
    }

    /**
     * The owner, or someone an agent they can use grants the file to. Which agents grant what is Chat's to decide
     * ({@link io.memoryos.library.FileAttachments}); the caller resolves it first and passes the granted ids, so the
     * file row is still read, and locked, in one statement.
     */
    private static final String READABLE = "(f.owner_actor_id=:actor OR f.id IN (:viaAgents))";

    /** An empty IN list is invalid SQL; the nil UUID never names a file. */
    private static Collection<UUID> granted(Collection<UUID> viaAgents) {
        return viaAgents.isEmpty() ? List.of(new UUID(0, 0)) : viaAgents;
    }

    public Optional<Row> readable(TenantId tenant, ActorId actor, UUID id, Collection<UUID> viaAgents,
                                  boolean lock) {
        return jdbc.sql("SELECT f.* FROM chat_user_file f WHERE f.tenant_id=:tenant AND f.id=:id AND " + READABLE
                        + (lock ? " FOR SHARE OF f" : ""))
                .param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .param("viaAgents", granted(viaAgents))
                .query((row, ignored) -> map(row)).optional();
    }

    /** The READY files among {@code ids} that the actor owns, in one query; unknown ids are absent. */
    public List<Row> owned(TenantId tenant, ActorId actor, Set<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT * FROM chat_user_file WHERE tenant_id=:tenant AND owner_actor_id=:actor"
                        + " AND id IN (:ids) AND status='READY'")
                .param("tenant", tenant.value()).param("actor", actor.value()).param("ids", ids)
                .query((row, ignored) -> map(row)).list();
    }

    /** The owner's most recent READY uploads that have an indexed document, the scope of a content search. */
    public List<UUID> searchable(TenantId tenant, ActorId actor, int limit) {
        return jdbc.sql("""
                SELECT id FROM chat_user_file WHERE tenant_id=:tenant AND owner_actor_id=:actor
                    AND status='READY' AND document_id IS NOT NULL
                ORDER BY created_at DESC, id LIMIT :limit
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("limit", limit)
                .query(UUID.class).list();
    }

    public List<UserFile> recent(TenantId tenant, ActorId actor, int offset, int limit) {
        return jdbc.sql("""
                SELECT * FROM chat_user_file WHERE tenant_id=:tenant AND owner_actor_id=:actor
                    AND status NOT IN ('DELETING','DELETED')
                ORDER BY created_at DESC,id OFFSET :offset LIMIT :limit
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("offset", offset).param("limit", limit)
                .query((row, ignored) -> map(row).file()).list();
    }

    public record TextWindow(String text, int offset, int totalCharacters) {}

    /**
     * What a serving read needs: the upload's own object, and the derived thumbnail once one has been written.
     * A thumbnail is absent until the library first asks for one, and for an upload whose bytes this build
     * cannot decode it stays absent.
     */
    public record Servable(StoredObjectReference reference, String mediaType, @Nullable ObjectKey thumbnailKey,
                           @Nullable String thumbnailMediaType) {}

    public Optional<Servable> raw(TenantId tenant, ActorId actor, UUID id, Collection<UUID> viaAgents) {
        return jdbc.sql("""
                SELECT o.*, f.media_type AS file_media_type, f.detected_media_type,
                       f.thumbnail_object_key, f.thumbnail_media_type
                FROM chat_user_file f JOIN stored_objects o ON o.tenant_id=f.tenant_id AND o.id=f.stored_object_id
                WHERE f.tenant_id=:tenant AND f.id=:id AND f.status='READY' AND o.state='ACTIVE' AND
                """ + READABLE).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .param("viaAgents", granted(viaAgents))
                .query((row, ignored) -> {
                    String detected = row.getString("detected_media_type");
                    String thumbnailKey = row.getString("thumbnail_object_key");
                    return new Servable(new StoredObjectReference(
                            new StoredObjectId(row.getObject("id", UUID.class)),
                            new ObjectKey(row.getString("object_key")), row.getString("filename"),
                            new ObjectMetadata(row.getLong("size_bytes"), row.getString("declared_media_type"),
                                    new ContentSha256(row.getString("content_sha256")))),
                            detected == null ? row.getString("file_media_type") : detected,
                            thumbnailKey == null ? null : new ObjectKey(thumbnailKey),
                            row.getString("thumbnail_media_type"));
                }).optional();
    }

    /**
     * Records the thumbnail written for an upload. Answers false when the upload already has one, which is how
     * two requests that rendered the same image at once settle: the loser releases the object it staged. A file
     * on its way out is never given one, so the byte release cannot be raced into leaving bytes behind.
     */
    public boolean attachThumbnail(TenantId tenant, UUID id, UUID storedObjectId, ObjectKey key, String mediaType) {
        return jdbc.sql("""
                UPDATE chat_user_file SET thumbnail_stored_object_id = :object, thumbnail_object_key = :key,
                    thumbnail_media_type = :type
                WHERE tenant_id = :tenant AND id = :id AND thumbnail_object_key IS NULL AND status = 'READY'
                """).param("tenant", tenant.value()).param("id", id).param("object", storedObjectId)
                .param("key", key.value()).param("type", mediaType).update() == 1;
    }

    public Map<UUID, UUID> documents(TenantId tenant, ActorId actor, Set<UUID> ids,
                                               Collection<UUID> viaAgents) {
        if (ids.isEmpty()) return Map.of();
        var result = new LinkedHashMap<UUID, UUID>();
        jdbc.sql("SELECT f.id,f.document_id FROM chat_user_file f WHERE f.tenant_id=:tenant AND f.id IN (:ids) AND f.status='READY' AND f.document_id IS NOT NULL AND " + READABLE)
                .param("tenant", tenant.value()).param("actor", actor.value()).param("ids", ids)
                .param("viaAgents", granted(viaAgents))
                .query((row, ignored) -> { result.put(row.getObject("id", UUID.class), row.getObject("document_id", UUID.class)); return true; }).list();
        return Map.copyOf(result);
    }

    public Optional<TextWindow> plaintext(TenantId tenant, ActorId actor, UUID id, Collection<UUID> viaAgents,
                                          int offset, int count) {
        return jdbc.sql("""
                SELECT substring(plaintext FROM :start FOR :count) AS text, length(plaintext) AS total
                FROM chat_user_file f WHERE f.tenant_id=:tenant AND f.id=:id
                    AND f.status='READY' AND f.plaintext IS NOT NULL AND
                """ + READABLE).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .param("viaAgents", granted(viaAgents)).param("start", offset + 1).param("count", count)
                .query((row, ignored) -> new TextWindow(row.getString("text"), offset, row.getInt("total"))).optional();
    }

    /** The opening {@code count} characters of each readable READY file among {@code ids}, in one query. */
    public Map<UUID, TextWindow> plaintexts(TenantId tenant, ActorId actor, Collection<UUID> ids,
                                                     Collection<UUID> viaAgents, int count) {
        if (ids.isEmpty()) return Map.of();
        var found = new HashMap<UUID, TextWindow>();
        jdbc.sql("""
                SELECT f.id, substring(plaintext FROM 1 FOR :count) AS text, length(plaintext) AS total
                FROM chat_user_file f WHERE f.tenant_id=:tenant AND f.id IN (:ids)
                    AND f.status='READY' AND f.plaintext IS NOT NULL AND
                """ + READABLE).param("tenant", tenant.value()).param("actor", actor.value()).param("ids", ids)
                .param("viaAgents", granted(viaAgents)).param("count", count)
                .query((row, ignored) -> found.put(row.getObject("id", UUID.class),
                        new TextWindow(row.getString("text"), 0, row.getInt("total"))))
                .list();
        return found;
    }

    public UUID create(TenantId tenant, ActorId actor, UUID request, ObjectUploadId upload, ObjectUploadSpecification spec) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO chat_user_file(id,tenant_id,owner_actor_id,request_id,upload_id,filename,media_type,size_bytes,content_sha256)
                VALUES(:id,:tenant,:actor,:request,:upload,:name,:type,:size,:sha)
                """).param("id", id).param("tenant", tenant.value()).param("actor", actor.value()).param("request", request)
                .param("upload", upload.value()).param("name", spec.filename()).param("type", spec.mediaType())
                .param("size", spec.sizeBytes()).param("sha", spec.checksum().value()).update();
        return id;
    }

    /** The live upload copied from an artifact, if the owner already has one (V92). */
    public Optional<Row> copy(TenantId tenant, ActorId actor, String source, UUID artifact) {
        return jdbc.sql("""
                SELECT * FROM chat_user_file WHERE tenant_id=:tenant AND owner_actor_id=:actor
                    AND copied_from_source=:source AND copied_from_id=:artifact AND status NOT IN ('DELETING','DELETED')
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("source", source)
                .param("artifact", artifact).query((row, ignored) -> map(row)).optional();
    }

    /**
     * A file whose bytes the server copied from an artifact into an object it wrote and adopted itself (V127). It
     * has no browser upload; from {@link #finalized} on it follows the ordinary file lifecycle.
     */
    public UUID createCopy(TenantId tenant, ActorId actor, StoredObjectReference object, String source, UUID artifact) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO chat_user_file(id,tenant_id,owner_actor_id,request_id,stored_object_id,filename,media_type,
                    size_bytes,content_sha256,copied_from_source,copied_from_id)
                VALUES(:id,:tenant,:actor,:request,:object,:name,:type,:size,:sha,:source,:artifact)
                """).param("id", id).param("tenant", tenant.value()).param("actor", actor.value())
                .param("request", UUID.randomUUID()).param("object", object.id().value()).param("name", object.filename())
                .param("type", object.metadata().mediaType()).param("size", object.metadata().sizeBytes())
                .param("sha", object.metadata().checksum().value()).param("source", source).param("artifact", artifact)
                .update();
        return id;
    }

    /** Hands an uploaded file to the file worker, recording the stored object its adoption made the file's own. */
    public void finalized(TenantId tenant, UUID id, StoredObjectId object) {
        if (jdbc.sql("""
                UPDATE chat_user_file SET status='PROCESSING',stored_object_id=:object,updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=:tenant AND id=:id AND status='UPLOADING'
                """).param("tenant", tenant.value()).param("id", id).param("object", object.value()).update() != 1) {
            throw new IllegalStateException("File upload is no longer pending");
        }
        enqueue(tenant, id, "PROCESS");
    }

    public void enqueue(TenantId tenant, UUID id, String action) {
        var origin = SourceOperationTraceContext.current();
        jdbc.sql("""
                INSERT INTO chat_file_work(id,tenant_id,file_id,action,origin_trace_id,origin_span_id)
                VALUES(:id,:tenant,:file,:action,:trace,:span)
                """).param("id", UUID.randomUUID()).param("tenant", tenant.value()).param("file", id).param("action", action)
                .param("trace", origin == null ? null : origin.traceId()).param("span", origin == null ? null : origin.spanId()).update();
    }

    public void retry(TenantId tenant, UUID id) {
        if (jdbc.sql("UPDATE chat_user_file SET status='PROCESSING',error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=:tenant AND id=:id AND status='FAILED'")
                .param("tenant", tenant.value()).param("id", id).update() != 1) throw new IllegalStateException("file is not failed");
        enqueue(tenant, id, "PROCESS");
    }

    /**
     * Moves an upload to the trash: it leaves every listing at once, and the byte-releasing work is queued only
     * when {@code trashFor} has passed, so the owner can restore it until then. An upload that never finished
     * uploading has nothing to release and is closed immediately.
     */
    public void delete(TenantId tenant, UUID id, boolean uploading, Duration trashFor) {
        jdbc.sql("""
                UPDATE chat_file_work SET status='CANCELLED',claim_token=NULL,lease_expires_at=NULL,
                    dispatch_token=NULL,dispatch_lease_expires_at=NULL,completed_at=CURRENT_TIMESTAMP
                WHERE tenant_id=:tenant AND file_id=:file AND status IN ('NOT_STARTED','IN_PROGRESS')
                """).param("tenant", tenant.value()).param("file", id).update();
        jdbc.sql("""
                UPDATE chat_user_file SET status=:status, updated_at=CURRENT_TIMESTAMP,
                    deleted_at=CURRENT_TIMESTAMP, purge_after=CURRENT_TIMESTAMP + make_interval(secs => :trash)
                WHERE tenant_id=:tenant AND id=:file
                """).param("status", uploading ? "DELETED" : "DELETING").param("trash", trashFor.toSeconds())
                .param("tenant", tenant.value()).param("file", id).update();
        if (!uploading && trashFor.isZero()) enqueue(tenant, id, "DELETE");
    }

    /**
     * Takes an upload out of the trash. Its document and extracted text were never removed, because the release
     * work had not run, so the file is usable again at once. False when it is not in the trash any more.
     */
    public boolean restore(TenantId tenant, ActorId actor, UUID id) {
        return jdbc.sql("""
                UPDATE chat_user_file SET status='READY', deleted_at=NULL, purge_after=NULL,
                    updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=:tenant AND owner_actor_id=:actor AND id=:id AND status='DELETING'
                  AND NOT EXISTS (SELECT 1 FROM chat_file_work w WHERE w.tenant_id=:tenant AND w.file_id=:id
                      AND w.action='DELETE' AND w.status IN ('NOT_STARTED','IN_PROGRESS','COMPLETED'))
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id).update() == 1;
    }

    /** Releases an upload's bytes now instead of when its trash window ends. */
    public boolean purgeNow(TenantId tenant, ActorId actor, UUID id) {
        boolean due = jdbc.sql("""
                UPDATE chat_user_file SET purge_after=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=:tenant AND owner_actor_id=:actor AND id=:id AND status='DELETING'
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id).update() == 1;
        if (due) enqueue(tenant, id, "DELETE");
        return due;
    }

    /** Every upload the owner has in the trash, so emptying it needs no client round trip per file. */
    public List<UUID> trashed(TenantId tenant, ActorId actor, int limit) {
        return jdbc.sql("""
                SELECT id FROM chat_user_file
                WHERE tenant_id=:tenant AND owner_actor_id=:actor AND status='DELETING'
                ORDER BY deleted_at, id LIMIT :limit
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("limit", limit)
                .query(UUID.class).list();
    }

    /**
     * Queues the byte-releasing work for uploads whose trash window has passed; the existing DELETE work then
     * owns the release and its retries. Returns how many were queued.
     */
    public int enqueueDuePurges(int limit) {
        var due = jdbc.sql("""
                SELECT tenant_id, id FROM chat_user_file
                WHERE status='DELETING' AND purge_after IS NOT NULL AND purge_after < CURRENT_TIMESTAMP
                  AND NOT EXISTS (SELECT 1 FROM chat_file_work w WHERE w.tenant_id=chat_user_file.tenant_id
                      AND w.file_id=chat_user_file.id AND w.action='DELETE'
                      AND w.status IN ('NOT_STARTED','IN_PROGRESS'))
                ORDER BY purge_after LIMIT :limit FOR UPDATE SKIP LOCKED
                """).param("limit", limit)
                .query((row, ignored) -> new AbstractMap.SimpleEntry<>(
                        new TenantId(row.getObject("tenant_id", UUID.class)), row.getObject("id", UUID.class)))
                .list();
        due.forEach(entry -> enqueue(entry.getKey(), entry.getValue(), "DELETE"));
        return due.size();
    }

    /**
     * Serializes a person's file writes with their Chat writes. The key is Chat's owner lock, deliberately the same
     * string: a turn admits files under it, so an upload deleted, restored or copied under the same lock cannot race
     * the admission. Changing it here without Chat would let the two stop excluding each other.
     */
    public void lockOwner(TenantId tenant, ActorId actor) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "chat-owner:" + tenant.value() + ":" + actor.value()).query(rs -> { rs.next(); return true; });
    }

    /**
     * Marks the uploads a temporary conversation's question carried as belonging to that conversation, so the
     * library stops listing them and the conversation's purge releases them with it.
     */
    public void claimTemporary(TenantId tenant, ActorId actor, UUID session, Collection<UUID> files) {
        if (files.isEmpty()) return;
        jdbc.sql("""
                UPDATE chat_user_file SET temporary_session_id = :session
                WHERE tenant_id = :tenant AND owner_actor_id = :actor AND id IN (:files)
                  AND temporary_session_id IS NULL
                """).param("session", session).param("tenant", tenant.value()).param("actor", actor.value())
                .param("files", files).update();
    }

    /**
     * Hands the uploads that belong to a temporary conversation to the file work, which releases their bytes
     * the way a deleted upload's are released. An ordinary conversation's uploads are never touched.
     */
    public int releaseTemporary(UUID session) {
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
                .query((row, ignored) -> new AbstractMap.SimpleEntry<>(
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

    private static Row map(ResultSet row) throws SQLException {
        UUID upload = row.getObject("upload_id", UUID.class);
        String detected = row.getString("detected_media_type");
        return new Row(new UserFile(row.getObject("id", UUID.class), row.getString("filename"), detected == null ? row.getString("media_type") : detected,
                row.getLong("size_bytes"), UserFile.Status.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(), row.getString("error_code")),
                upload == null ? null : new ObjectUploadId(upload), new ContentSha256(row.getString("content_sha256")), row.getString("media_type"));
    }
}
