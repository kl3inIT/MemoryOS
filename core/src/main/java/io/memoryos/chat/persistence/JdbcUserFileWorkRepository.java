package io.memoryos.chat.persistence;

import io.memoryos.chat.UserFileWork;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcUserFileWorkRepository {
    private final JdbcClient jdbc;
    public JdbcUserFileWorkRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public int expireUploads(int limit) {
        return jdbc.sql("""
                WITH expired AS (
                    SELECT f.id FROM chat_user_file f JOIN object_uploads u ON u.tenant_id=f.tenant_id AND u.id=f.upload_id
                    WHERE f.status='UPLOADING' AND u.status='EXPIRED' ORDER BY f.created_at,f.id
                    LIMIT :limit FOR UPDATE OF f SKIP LOCKED
                )
                UPDATE chat_user_file SET status='FAILED',error_code='UPLOAD_EXPIRED',updated_at=CURRENT_TIMESTAMP
                WHERE id IN (SELECT id FROM expired)
                """).param("limit", Math.clamp(limit, 1, 100)).update();
    }

    public Optional<UserFileWork> claim(TenantId tenant, UUID operation, UUID delivery) {
        var token = UUID.randomUUID();
        return jdbc.sql("""
                WITH claimed AS (
                    UPDATE chat_file_work SET status='IN_PROGRESS',claim_token=:token,
                        lease_expires_at=CURRENT_TIMESTAMP+INTERVAL '2 minutes',processing_attempts=processing_attempts+1
                    WHERE tenant_id=:tenant AND id=:operation AND delivery_id=:delivery
                        AND (status='NOT_STARTED' OR (status='IN_PROGRESS' AND lease_expires_at<CURRENT_TIMESTAMP))
                    RETURNING *
                )
                SELECT work.*,f.owner_actor_id,o.id AS object_id,o.object_key,o.filename,o.size_bytes,o.declared_media_type,o.content_sha256
                FROM claimed work JOIN chat_user_file f ON f.tenant_id=work.tenant_id AND f.id=work.file_id
                JOIN object_uploads u ON u.tenant_id=f.tenant_id AND u.id=f.upload_id
                JOIN stored_objects o ON o.tenant_id=u.tenant_id AND o.id=u.stored_object_id
                """).param("tenant", tenant.value()).param("operation", operation).param("delivery", delivery).param("token", token)
                .query((r, ignored) -> new UserFileWork(tenant, new ActorId(r.getObject("owner_actor_id", UUID.class)),
                        operation, r.getObject("file_id", UUID.class), token, UserFileWork.Action.valueOf(r.getString("action")),
                        r.getInt("processing_attempts"), new StoredObjectReference(new StoredObjectId(r.getObject("object_id", UUID.class)),
                        new ObjectKey(r.getString("object_key")), r.getString("filename"),
                        new ObjectMetadata(r.getLong("size_bytes"), r.getString("declared_media_type"),
                                new ContentSha256(r.getString("content_sha256")))))).optional();
    }

    public boolean renew(UserFileWork work) {
        return jdbc.sql("""
                UPDATE chat_file_work SET lease_expires_at=CURRENT_TIMESTAMP+INTERVAL '2 minutes'
                WHERE id=:id AND tenant_id=:tenant AND claim_token=:token AND status='IN_PROGRESS'
                    AND lease_expires_at>=CURRENT_TIMESTAMP
                """).param("id", work.operationId()).param("tenant", work.tenantId().value()).param("token", work.token()).update() == 1;
    }

    public boolean lockCurrent(UserFileWork work) {
        // The file is always locked before its work row, also in delete/admission paths.
        return jdbc.sql("""
                SELECT f.id FROM chat_user_file f JOIN chat_file_work w ON w.tenant_id=f.tenant_id AND w.file_id=f.id
                WHERE f.tenant_id=:tenant AND f.id=:file AND w.id=:id AND w.claim_token=:token
                    AND w.status='IN_PROGRESS' AND w.lease_expires_at>=CURRENT_TIMESTAMP
                    AND ((w.action='PROCESS' AND f.status='PROCESSING') OR (w.action='DELETE' AND f.status='DELETING'))
                FOR UPDATE OF f,w
                """).param("tenant", work.tenantId().value()).param("file", work.fileId()).param("id", work.operationId())
                .param("token", work.token()).query(UUID.class).optional().isPresent();
    }

    public void completed(UserFileWork work, DocumentId document, String plaintext, String mediaType) {
        jdbc.sql("""
                UPDATE chat_user_file SET status='READY',document_id=:document,plaintext=:text,detected_media_type=:media,error_code=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=:tenant AND id=:file
                """).param("document", document.value()).param("text", plaintext).param("media", mediaType)
                .param("tenant", work.tenantId().value()).param("file", work.fileId()).update();
        finish(work, "COMPLETED");
    }

    public record DeletedReferences(ObjectUploadId upload, @Nullable DocumentId document) {}

    public DeletedReferences detach(UserFileWork work) {
        var refs = jdbc.sql("SELECT upload_id,document_id FROM chat_user_file WHERE tenant_id=:tenant AND id=:file")
                .param("tenant", work.tenantId().value()).param("file", work.fileId()).query((r, ignored) -> {
                    var document = r.getObject("document_id", UUID.class);
                    return new DeletedReferences(new ObjectUploadId(r.getObject("upload_id", UUID.class)),
                            document == null ? null : new DocumentId(document));
                }).single();
        jdbc.sql("""
                UPDATE chat_user_file SET status='DELETED',document_id=NULL,plaintext=NULL,updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=:tenant AND id=:file
                """).param("tenant", work.tenantId().value()).param("file", work.fileId()).update();
        finish(work, "COMPLETED");
        return refs;
    }

    public void failed(UserFileWork work, String code) {
        if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("invalid file error code");
        if (!lockCurrent(work)) return;
        boolean terminal = work.action() == UserFileWork.Action.PROCESS && work.attempts() >= 3;
        int changed = jdbc.sql("""
                UPDATE chat_file_work SET status=:status,claim_token=NULL,lease_expires_at=NULL,error_code=:code,
                    next_dispatch_at=CURRENT_TIMESTAMP+INTERVAL '5 seconds',dispatch_token=NULL,dispatch_lease_expires_at=NULL
                WHERE id=:id AND tenant_id=:tenant AND claim_token=:token AND status='IN_PROGRESS'
                    AND lease_expires_at>=CURRENT_TIMESTAMP
                """).param("status", terminal ? "FAILED" : "NOT_STARTED").param("code", code)
                .param("id", work.operationId()).param("tenant", work.tenantId().value()).param("token", work.token()).update();
        if (changed == 1 && terminal) {
            jdbc.sql("""
                    UPDATE chat_user_file SET status='FAILED',error_code=:code,updated_at=CURRENT_TIMESTAMP
                    WHERE tenant_id=:tenant AND id=:file AND status='PROCESSING'
                    """).param("code", code).param("tenant", work.tenantId().value()).param("file", work.fileId()).update();
        }
    }

    private void finish(UserFileWork work, String status) {
        jdbc.sql("""
                UPDATE chat_file_work SET status=:status,claim_token=NULL,lease_expires_at=NULL,
                    dispatch_token=NULL,dispatch_lease_expires_at=NULL,completed_at=CURRENT_TIMESTAMP
                WHERE tenant_id=:tenant AND id=:id AND claim_token=:token
                """).param("status", status).param("tenant", work.tenantId().value()).param("id", work.operationId())
                .param("token", work.token()).update();
    }
}
