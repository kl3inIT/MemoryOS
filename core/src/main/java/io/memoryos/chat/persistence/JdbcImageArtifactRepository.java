package io.memoryos.chat.persistence;

import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.objectstorage.ObjectKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcImageArtifactRepository {
    private final JdbcClient jdbc;

    public JdbcImageArtifactRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Artifact(UUID id, String mediaType, @Nullable String revisedPrompt, boolean deleted) {}
    public record Content(ObjectKey key, String mediaType) {}

    /**
     * What a serving read needs: the artifact's own object, and the derived thumbnail once one has been
     * written. A thumbnail is absent until the library first asks for one, and for an artifact whose bytes
     * this build cannot decode it stays absent.
     */
    public record Servable(ObjectKey key, String mediaType,
                           @Nullable ObjectKey thumbnailKey, @Nullable String thumbnailMediaType) {}

    /** Who owns the conversation an answer belongs to; their library holds whatever that answer generates. */
    public Optional<UUID> owner(TenantId tenant, UUID messageId) {
        return jdbc.sql("""
                SELECT s.owner_actor_id FROM chat_message m JOIN chat_session s ON s.id = m.session_id
                WHERE m.id = :message AND s.tenant_id = :tenant
                """).param("message", messageId).param("tenant", tenant.value())
                .query((row, ignored) -> row.getObject("owner_actor_id", UUID.class)).optional();
    }

    /**
     * Records the image against its answer. Owner, conversation and the library name come from the answer
     * itself, so the file library reads them without joining and a caller cannot record a foreign owner.
     */
    public void insert(TenantId tenant, UUID messageId, UUID id, UUID storedObjectId, ObjectKey key,
                       String mediaType, String extension, long sizeBytes, @Nullable String revisedPrompt,
                       @Nullable UUID sourceArtifactId, @Nullable UUID sourceFileId) {
        int inserted = jdbc.sql("""
                INSERT INTO chat_image_artifact(id,tenant_id,message_id,stored_object_id,object_key,media_type,revised_prompt,
                                                source_artifact_id,source_file_id,owner_actor_id,session_id,filename,size_bytes)
                SELECT :id,:tenant,:message,:object,:key,:type,:revised,:sourceArtifact,:sourceFile,s.owner_actor_id,s.id,
                       'image-' || to_char(CURRENT_TIMESTAMP AT TIME ZONE 'UTC','YYYYMMDD-HH24MISS') || '-'
                           || substr(replace(CAST(:id AS text),'-',''),1,8) || :extension,
                       :size
                FROM chat_message m JOIN chat_session s ON s.id = m.session_id
                WHERE m.id = :message AND s.tenant_id = :tenant
                """).param("id", id).param("tenant", tenant.value()).param("message", messageId)
                .param("object", storedObjectId).param("key", key.value()).param("type", mediaType)
                .param("revised", revisedPrompt).param("sourceArtifact", sourceArtifactId).param("sourceFile", sourceFileId)
                .param("extension", extension).param("size", sizeBytes).update();
        if (inserted != 1) throw new IllegalStateException("generated image has no answer in this tenant");
    }

    /**
     * Images of an already-authorized page of messages. History passes {@code includeDeleted} so an answer keeps
     * a deleted image as a tombstone; a turn context must not, or the model would be offered an image to edit
     * that no longer exists.
     */
    public Map<UUID, List<Artifact>> byMessages(TenantId tenant, Collection<UUID> messageIds, boolean includeDeleted) {
        if (messageIds.isEmpty()) return Map.of();
        var result = new LinkedHashMap<UUID, List<Artifact>>();
        jdbc.sql("""
                SELECT message_id, id, media_type, revised_prompt, deleted_at IS NOT NULL AS deleted FROM chat_image_artifact
                WHERE tenant_id = :tenant AND message_id IN (:messages) AND (:includeDeleted OR deleted_at IS NULL)
                ORDER BY created_at, id
                """).param("tenant", tenant.value()).param("messages", messageIds).param("includeDeleted", includeDeleted)
                .query((row, ignored) -> {
                    result.computeIfAbsent(row.getObject("message_id", UUID.class), key -> new ArrayList<>())
                            .add(new Artifact(row.getObject("id", UUID.class), row.getString("media_type"),
                                    row.getString("revised_prompt"), row.getBoolean("deleted")));
                    return true;
                }).list();
        return result;
    }

    /**
     * Hides the image from the library and every serving route; a worker sweep releases its bytes. Deleting an
     * image the sweep has already removed still succeeds: an absent row is the outcome the caller asked for.
     */
    public boolean markDeleted(TenantId tenant, ActorId actor, UUID id, java.time.Duration trashFor) {
        boolean hidden = jdbc.sql("""
                UPDATE chat_image_artifact SET deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP),
                    purge_after = COALESCE(purge_after, CURRENT_TIMESTAMP + make_interval(secs => :trash))
                WHERE tenant_id = :tenant AND id = :id AND owner_actor_id = :actor
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .param("trash", trashFor.toSeconds()).update() == 1;
        return hidden || jdbc.sql("SELECT count(*) FROM chat_image_artifact WHERE id = :id")
                .param("id", id).query(Long.class).single() == 0;
    }

    /** Takes a generated image out of the trash while its bytes are still there and no purge is due. */
    public boolean restore(TenantId tenant, ActorId actor, UUID id) {
        return jdbc.sql("""
                UPDATE chat_image_artifact SET deleted_at = NULL, purge_after = NULL, cleanup_token = NULL,
                    cleanup_until = NULL
                WHERE tenant_id = :tenant AND id = :id AND owner_actor_id = :actor AND deleted_at IS NOT NULL AND purged_at IS NULL
                  AND (purge_after IS NULL OR purge_after > CURRENT_TIMESTAMP)
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id).update() == 1;
    }

    /** Lets the sweep release a trashed image's bytes now instead of when its window ends. */
    public boolean purgeNow(TenantId tenant, ActorId actor, UUID id) {
        return jdbc.sql("""
                UPDATE chat_image_artifact SET purge_after = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND id = :id AND owner_actor_id = :actor AND deleted_at IS NOT NULL AND purged_at IS NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id).update() == 1;
    }

    /** Ends the window for every trashed image of this owner; answers how many. */
    public int purgeTrashed(TenantId tenant, ActorId actor, int limit) {
        return jdbc.sql("""
                UPDATE chat_image_artifact SET purge_after = CURRENT_TIMESTAMP
                WHERE (tenant_id, id) IN (
                    SELECT tenant_id, id FROM chat_image_artifact
                    WHERE tenant_id = :tenant AND owner_actor_id = :actor AND deleted_at IS NOT NULL AND purged_at IS NULL
                    ORDER BY deleted_at LIMIT :limit)
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("limit", limit).update();
    }

    public List<Artifact> byMessage(TenantId tenant, UUID messageId) {
        return jdbc.sql("""
                SELECT id, media_type, revised_prompt FROM chat_image_artifact
                WHERE tenant_id = :tenant AND message_id = :message AND deleted_at IS NULL ORDER BY created_at, id
                """).param("tenant", tenant.value()).param("message", messageId)
                .query((row, ignored) -> new Artifact(row.getObject("id", UUID.class), row.getString("media_type"),
                        row.getString("revised_prompt"), false))
                .list();
    }

    /** Serving lookup: the actor must own the chat that produced the artifact. */
    public Optional<Servable> owned(TenantId tenant, ActorId actor, UUID id) {
        return jdbc.sql("""
                SELECT a.object_key, a.media_type, a.thumbnail_object_key, a.thumbnail_media_type
                FROM chat_image_artifact a
                JOIN chat_message m ON m.id = a.message_id
                JOIN chat_session s ON s.id = m.session_id AND s.tenant_id = a.tenant_id
                WHERE a.tenant_id = :tenant AND a.id = :id AND s.owner_actor_id = :actor AND s.deleted_at IS NULL
                  AND a.deleted_at IS NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .query((row, ignored) -> {
                    String thumbnailKey = row.getString("thumbnail_object_key");
                    return new Servable(new ObjectKey(row.getString("object_key")), row.getString("media_type"),
                            thumbnailKey == null ? null : new ObjectKey(thumbnailKey), row.getString("thumbnail_media_type"));
                })
                .optional();
    }

    /**
     * Records the thumbnail written for an artifact. Answers false when the artifact already has one, which is
     * how two requests that rendered the same image at once settle: the loser releases the object it staged.
     * A deleted artifact is never given one, so the sweep cannot be raced into leaving bytes behind.
     */
    public boolean attachThumbnail(TenantId tenant, UUID id, UUID storedObjectId, ObjectKey key, String mediaType) {
        return jdbc.sql("""
                UPDATE chat_image_artifact SET thumbnail_stored_object_id = :object, thumbnail_object_key = :key,
                    thumbnail_media_type = :type
                WHERE tenant_id = :tenant AND id = :id AND thumbnail_object_key IS NULL AND deleted_at IS NULL
                """).param("tenant", tenant.value()).param("id", id).param("object", storedObjectId)
                .param("key", key.value()).param("type", mediaType).update() == 1;
    }

    /** Edit-source lookup: an image generated in this owner's session, never one from another conversation. */
    public Optional<Content> inSession(TenantId tenant, ActorId actor, UUID session, UUID id) {
        return jdbc.sql("""
                SELECT a.object_key, a.media_type FROM chat_image_artifact a
                JOIN chat_message m ON m.id = a.message_id
                JOIN chat_session s ON s.id = m.session_id AND s.tenant_id = a.tenant_id
                WHERE a.tenant_id = :tenant AND a.id = :id AND s.id = :session
                  AND s.owner_actor_id = :actor AND s.deleted_at IS NULL AND a.deleted_at IS NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("session", session).param("id", id)
                .query((row, ignored) -> new Content(new ObjectKey(row.getString("object_key")), row.getString("media_type")))
                .optional();
    }
}
