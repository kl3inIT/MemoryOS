package io.memoryos.chat.files.persistence;

import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.StoredObjectId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Deletion claims over the two Chat artifact tables. Releasing the bytes does not remove the row: it becomes a
 * tombstone, with its storage columns emptied and {@code purged_at} set, so the answer that generated the
 * image or the file keeps saying it was deleted however long ago that was. The claim lease, not the row's
 * absence, is what stops two workers, and a tombstone is never claimed again.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChatArtifactCleanupRepository {
    private final JdbcClient jdbc;

    public JdbcChatArtifactCleanupRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public enum Kind { GENERATED_FILE, IMAGE }

    /**
     * {@code preview} is the second object an artifact owns: the converted PDF of a presentation (V73) for a
     * generated file, and the library thumbnail (V100) for an image. Both are released with the artifact.
     */
    public record Claim(Kind kind, TenantId tenantId, UUID id, UUID token,
                        StoredObjectId object, ObjectKey key,
                        @Nullable StoredObjectId previewObject, @Nullable ObjectKey previewKey) {}

    /**
     * Half the batch is reserved for each table, and whatever one table leaves unused goes to the other, so a
     * backlog of deleted files cannot keep deleted images waiting run after run.
     */
    public List<Claim> claim(int limit) {
        var claims = new ArrayList<Claim>(limit);
        claims.addAll(claimGeneratedFiles((limit + 1) / 2));
        claims.addAll(claimImages(limit - claims.size()));
        if (claims.size() < limit) claims.addAll(claimGeneratedFiles(limit - claims.size()));
        return List.copyOf(claims);
    }

    private List<Claim> claimGeneratedFiles(int limit) {
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT a.tenant_id, a.id FROM chat_file_artifact a
                    WHERE a.deleted_at IS NOT NULL AND a.purged_at IS NULL
                      AND (a.purge_after IS NULL OR a.purge_after < CURRENT_TIMESTAMP)
                      AND (a.cleanup_until IS NULL OR a.cleanup_until < CURRENT_TIMESTAMP)
                    ORDER BY a.deleted_at LIMIT :limit FOR UPDATE SKIP LOCKED
                )
                UPDATE chat_file_artifact a SET cleanup_token = gen_random_uuid(),
                    cleanup_until = CURRENT_TIMESTAMP + INTERVAL '2' MINUTE
                FROM candidates c WHERE a.tenant_id = c.tenant_id AND a.id = c.id
                RETURNING a.tenant_id, a.id, a.cleanup_token, a.stored_object_id, a.object_key,
                          a.preview_stored_object_id, a.preview_object_key
                """).param("limit", limit).query((row, ignored) -> map(Kind.GENERATED_FILE, row)).list();
    }

    private List<Claim> claimImages(int limit) {
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT a.tenant_id, a.id FROM chat_image_artifact a
                    WHERE a.deleted_at IS NOT NULL AND a.purged_at IS NULL
                      AND (a.purge_after IS NULL OR a.purge_after < CURRENT_TIMESTAMP)
                      AND (a.cleanup_until IS NULL OR a.cleanup_until < CURRENT_TIMESTAMP)
                    ORDER BY a.deleted_at LIMIT :limit FOR UPDATE SKIP LOCKED
                )
                UPDATE chat_image_artifact a SET cleanup_token = gen_random_uuid(),
                    cleanup_until = CURRENT_TIMESTAMP + INTERVAL '2' MINUTE
                FROM candidates c WHERE a.tenant_id = c.tenant_id AND a.id = c.id
                RETURNING a.tenant_id, a.id, a.cleanup_token, a.stored_object_id, a.object_key,
                          a.thumbnail_stored_object_id AS preview_stored_object_id,
                          a.thumbnail_object_key AS preview_object_key
                """).param("limit", limit).query((row, ignored) -> map(Kind.IMAGE, row)).list();
    }

    /**
     * Turns the row into a tombstone, only while this worker still holds the claim it was given. What is kept
     * is what an answer needs to explain itself — the name, the size and the message it belongs to — and what
     * goes is everything that points at bytes, so nothing can serve, restore or copy it afterwards.
     */
    public boolean remove(Claim claim) {
        boolean image = claim.kind() == Kind.IMAGE;
        String table = image ? "chat_image_artifact" : "chat_file_artifact";
        // V73 and V106 each keep their three columns all set or all empty, so they go together.
        String preview = image
                ? ", thumbnail_stored_object_id = NULL, thumbnail_object_key = NULL, thumbnail_media_type = NULL"
                : ", preview_stored_object_id = NULL, preview_object_key = NULL, preview_size_bytes = NULL";
        return jdbc.sql(("""
                UPDATE %s SET purged_at = CURRENT_TIMESTAMP, stored_object_id = NULL, object_key = NULL,
                    cleanup_token = NULL, cleanup_until = NULL%s
                WHERE tenant_id = :tenant AND id = :id AND cleanup_token = :token
                """).formatted(table, preview))
                .param("tenant", claim.tenantId().value()).param("id", claim.id()).param("token", claim.token())
                .update() == 1;
    }

    private static Claim map(Kind kind, ResultSet row) throws SQLException {
        String previewKey = row.getString("preview_object_key");
        UUID previewObject = row.getObject("preview_stored_object_id", UUID.class);
        return new Claim(kind, new TenantId(row.getObject("tenant_id", UUID.class)), row.getObject("id", UUID.class),
                row.getObject("cleanup_token", UUID.class),
                new StoredObjectId(row.getObject("stored_object_id", UUID.class)), new ObjectKey(row.getString("object_key")),
                previewObject == null ? null : new StoredObjectId(previewObject),
                previewKey == null ? null : new ObjectKey(previewKey));
    }
}
