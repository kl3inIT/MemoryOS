package io.memoryos.chat.persistence;

import io.memoryos.chat.UserFile;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcUserFileRepository {
    private final JdbcClient jdbc;

    public JdbcUserFileRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Row(UserFile file, ObjectUploadId uploadId, ContentSha256 checksum, String declaredMediaType) {}

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
     * The owner, or anyone who can use a non-deleted agent that attaches the file or shows it as its avatar (Onyx
     * re-syncs agent files to sharees). Agent managers get no extra file authority here.
     */
    private static final String READABLE = """
            (f.owner_actor_id=:actor OR EXISTS (SELECT 1 FROM persona p WHERE p.tenant_id=f.tenant_id AND p.deleted_at IS NULL
                AND (p.file_ids @> jsonb_build_array(f.id::text) OR p.avatar_file_id=f.id) AND %s))
            """.formatted(AgentAccessSql.USES.replace(":agentsManage", "FALSE"));

    public Optional<Row> readable(TenantId tenant, ActorId actor, UUID id, boolean lock) {
        return jdbc.sql("SELECT f.* FROM chat_user_file f WHERE f.tenant_id=:tenant AND f.id=:id AND " + READABLE
                        + (lock ? " FOR SHARE OF f" : ""))
                .param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .query((row, ignored) -> map(row)).optional();
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

    public Optional<io.memoryos.objectstorage.StoredObjectReference> raw(TenantId tenant, ActorId actor, UUID id) {
        return jdbc.sql("""
                SELECT o.* FROM chat_user_file f JOIN object_uploads u ON u.tenant_id=f.tenant_id AND u.id=f.upload_id
                JOIN stored_objects o ON o.tenant_id=u.tenant_id AND o.id=u.stored_object_id
                WHERE f.tenant_id=:tenant AND f.id=:id AND f.status='READY' AND u.status='ADOPTED' AND
                """ + READABLE).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .query((row, ignored) -> new io.memoryos.objectstorage.StoredObjectReference(
                        new io.memoryos.objectstorage.StoredObjectId(row.getObject("id", UUID.class)),
                        new io.memoryos.objectstorage.ObjectKey(row.getString("object_key")), row.getString("filename"),
                        new io.memoryos.objectstorage.ObjectMetadata(row.getLong("size_bytes"), row.getString("declared_media_type"),
                                new ContentSha256(row.getString("content_sha256"))))).optional();
    }

    public java.util.Map<UUID, UUID> documents(TenantId tenant, ActorId actor, java.util.Set<UUID> ids) {
        if (ids.isEmpty()) return java.util.Map.of();
        var result = new java.util.LinkedHashMap<UUID, UUID>();
        jdbc.sql("SELECT f.id,f.document_id FROM chat_user_file f WHERE f.tenant_id=:tenant AND f.id IN (:ids) AND f.status='READY' AND f.document_id IS NOT NULL AND " + READABLE)
                .param("tenant", tenant.value()).param("actor", actor.value()).param("ids", ids)
                .query((row, ignored) -> { result.put(row.getObject("id", UUID.class), row.getObject("document_id", UUID.class)); return true; }).list();
        return java.util.Map.copyOf(result);
    }

    public Optional<TextWindow> plaintext(TenantId tenant, ActorId actor, UUID id, int offset, int count) {
        return jdbc.sql("""
                SELECT substring(plaintext FROM :start FOR :count) AS text, length(plaintext) AS total
                FROM chat_user_file f WHERE f.tenant_id=:tenant AND f.id=:id
                    AND f.status='READY' AND f.plaintext IS NOT NULL AND
                """ + READABLE).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .param("start", offset + 1).param("count", count)
                .query((row, ignored) -> new TextWindow(row.getString("text"), offset, row.getInt("total"))).optional();
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

    public void finalized(TenantId tenant, UUID id) {
        if (jdbc.sql("""
                UPDATE chat_user_file SET status='PROCESSING',updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=:tenant AND id=:id AND status='UPLOADING'
                """).param("tenant", tenant.value()).param("id", id).update() != 1) {
            throw new IllegalStateException("File upload is no longer pending");
        }
        enqueue(tenant, id, "PROCESS");
    }

    public void enqueue(TenantId tenant, UUID id, String action) {
        var origin = io.memoryos.connector.SourceOperationTraceContext.current();
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

    public void delete(TenantId tenant, UUID id, boolean uploading) {
        jdbc.sql("""
                UPDATE chat_file_work SET status='CANCELLED',claim_token=NULL,lease_expires_at=NULL,
                    dispatch_token=NULL,dispatch_lease_expires_at=NULL,completed_at=CURRENT_TIMESTAMP
                WHERE tenant_id=:tenant AND file_id=:file AND status IN ('NOT_STARTED','IN_PROGRESS')
                """).param("tenant", tenant.value()).param("file", id).update();
        jdbc.sql("UPDATE chat_user_file SET status=:status,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=:tenant AND id=:file")
                .param("status", uploading ? "DELETED" : "DELETING").param("tenant", tenant.value()).param("file", id).update();
        if (!uploading) enqueue(tenant, id, "DELETE");
    }

    public boolean usedByWorkspace(TenantId tenant, UUID id) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM persona WHERE tenant_id=:tenant AND deleted_at IS NULL AND (file_ids @> CAST(:file AS jsonb) OR avatar_file_id=:id))
                    OR EXISTS(SELECT 1 FROM chat_project WHERE tenant_id=:tenant AND file_ids @> CAST(:file AS jsonb))
                """).param("tenant", tenant.value()).param("file", "[\"" + id + "\"]").param("id", id).query(Boolean.class).single();
    }

    private static Row map(ResultSet row) throws SQLException {
        String detected = row.getString("detected_media_type");
        return new Row(new UserFile(row.getObject("id", UUID.class), row.getString("filename"), detected == null ? row.getString("media_type") : detected,
                row.getLong("size_bytes"), UserFile.Status.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(), row.getString("error_code")),
                new ObjectUploadId(row.getObject("upload_id", UUID.class)), new ContentSha256(row.getString("content_sha256")), row.getString("media_type"));
    }
}
