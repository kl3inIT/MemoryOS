package io.memoryos.chat.interpreter.persistence;

import io.memoryos.chat.interpreter.GeneratedFile;
import io.memoryos.chat.interpreter.InterpreterArtifact;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ObjectKey;
import java.time.Duration;
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
public class JdbcInterpreterRepository {
    private final JdbcClient jdbc;

    public JdbcInterpreterRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Setting(boolean enabled, long revision) {}

    /**
     * Generated files of an already-authorized page of messages, keyed by message id. A file deleted from the
     * library is still returned, marked deleted, so the answer keeps its card instead of losing it silently.
     */
    public Map<UUID, List<GeneratedFile>> byMessages(TenantId tenant, Collection<UUID> messageIds) {
        if (messageIds.isEmpty()) return Map.of();
        var result = new LinkedHashMap<UUID, List<GeneratedFile>>();
        jdbc.sql("""
                SELECT message_id, id, filename, media_type, size_bytes, chart IS NOT NULL AS has_chart,
                       deleted_at IS NOT NULL AS deleted
                FROM chat_file_artifact
                WHERE tenant_id = :tenant AND message_id IN (:messages) ORDER BY created_at, id
                """).param("tenant", tenant.value()).param("messages", messageIds)
                .query((row, ignored) -> {
                    boolean deleted = row.getBoolean("deleted");
                    result.computeIfAbsent(row.getObject("message_id", UUID.class), _ -> new ArrayList<>())
                            .add(new GeneratedFile(row.getObject("id", UUID.class), row.getString("filename"),
                                    row.getString("media_type"), deleted ? 0 : row.getLong("size_bytes"),
                                    !deleted && row.getBoolean("has_chart"), deleted));
                    return true;
                }).list();
        return result;
    }

    public Optional<Setting> setting(TenantId tenant) {
        return jdbc.sql("SELECT enabled, revision FROM chat_interpreter_setting WHERE tenant_id = :tenant")
                .param("tenant", tenant.value())
                .query((row, ignored) -> new Setting(row.getBoolean("enabled"), row.getLong("revision"))).optional();
    }

    /** The caller holds the Tenant's exclusive IAM lock and has checked the expected revision. */
    public Setting save(TenantId tenant, boolean enabled) {
        return jdbc.sql("""
                INSERT INTO chat_interpreter_setting(tenant_id, enabled, revision) VALUES(:tenant, :enabled, 1)
                ON CONFLICT (tenant_id) DO UPDATE SET enabled = EXCLUDED.enabled,
                    revision = chat_interpreter_setting.revision + 1, updated_at = now()
                RETURNING enabled, revision
                """).param("tenant", tenant.value()).param("enabled", enabled)
                .query((row, ignored) -> new Setting(row.getBoolean("enabled"), row.getLong("revision"))).single();
    }

    /** Who owns the conversation an answer belongs to; their library holds whatever that answer generates. */
    public Optional<UUID> owner(TenantId tenant, UUID messageId) {
        return jdbc.sql("""
                SELECT s.owner_actor_id FROM chat_message m JOIN chat_session s ON s.id = m.session_id
                WHERE m.id = :message AND s.tenant_id = :tenant
                """).param("message", messageId).param("tenant", tenant.value())
                .query((row, ignored) -> row.getObject("owner_actor_id", UUID.class)).optional();
    }

    /**
     * Records the file against its answer. Owner and conversation come from the answer itself, so the file
     * library reads them without joining and a caller cannot record a foreign owner.
     */
    public void insertArtifact(TenantId tenant, UUID messageId, UUID id, UUID storedObjectId, ObjectKey key,
                               String filename, String mediaType, long sizeBytes, @Nullable String chart) {
        int inserted = jdbc.sql("""
                INSERT INTO chat_file_artifact(id, tenant_id, message_id, stored_object_id, object_key, filename, media_type,
                                               size_bytes, chart, owner_actor_id, session_id)
                SELECT :id, :tenant, :message, :object, :key, :filename, :type, :size, CAST(:chart AS jsonb),
                       s.owner_actor_id, s.id
                FROM chat_message m JOIN chat_session s ON s.id = m.session_id
                WHERE m.id = :message AND s.tenant_id = :tenant
                """).param("id", id).param("tenant", tenant.value()).param("message", messageId).param("object", storedObjectId)
                .param("key", key.value()).param("filename", filename).param("type", mediaType).param("size", sizeBytes)
                .param("chart", chart).update();
        if (inserted != 1) throw new IllegalStateException("generated file has no answer in this tenant");
    }

    /**
     * Hides the file from the library and every serving route; a worker sweep releases its bytes. Deleting a
     * file the sweep has already removed still succeeds: an absent row is the outcome the caller asked for.
     */
    public boolean markArtifactDeleted(TenantId tenant, ActorId actor, UUID id, Duration trashFor) {
        boolean hidden = jdbc.sql("""
                UPDATE chat_file_artifact SET deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP),
                    purge_after = COALESCE(purge_after, CURRENT_TIMESTAMP + make_interval(secs => :trash))
                WHERE tenant_id = :tenant AND id = :id AND owner_actor_id = :actor
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .param("trash", trashFor.toSeconds()).update() == 1;
        return hidden || jdbc.sql("SELECT count(*) FROM chat_file_artifact WHERE id = :id")
                .param("id", id).query(Long.class).single() == 0;
    }

    /** Takes a generated file out of the trash while its bytes are still there and no purge is due. */
    public boolean restoreArtifact(TenantId tenant, ActorId actor, UUID id) {
        return jdbc.sql("""
                UPDATE chat_file_artifact SET deleted_at = NULL, purge_after = NULL, cleanup_token = NULL,
                    cleanup_until = NULL
                WHERE tenant_id = :tenant AND id = :id AND owner_actor_id = :actor AND deleted_at IS NOT NULL AND purged_at IS NULL
                  AND (purge_after IS NULL OR purge_after > CURRENT_TIMESTAMP)
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id).update() == 1;
    }

    /** Lets the sweep release a trashed generated file's bytes now instead of when its window ends. */
    public boolean purgeArtifactNow(TenantId tenant, ActorId actor, UUID id) {
        return jdbc.sql("""
                UPDATE chat_file_artifact SET purge_after = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND id = :id AND owner_actor_id = :actor AND deleted_at IS NOT NULL AND purged_at IS NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id).update() == 1;
    }

    /** Ends the window for every trashed generated file of this owner; answers how many. */
    public int purgeTrashedArtifacts(TenantId tenant, ActorId actor, int limit) {
        return jdbc.sql("""
                UPDATE chat_file_artifact SET purge_after = CURRENT_TIMESTAMP
                WHERE (tenant_id, id) IN (
                    SELECT tenant_id, id FROM chat_file_artifact
                    WHERE tenant_id = :tenant AND owner_actor_id = :actor AND deleted_at IS NOT NULL AND purged_at IS NULL
                    ORDER BY deleted_at LIMIT :limit)
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("limit", limit).update();
    }

    /** Chart data of a generated file the actor owns, as JSON text. */
    public Optional<String> ownedChart(TenantId tenant, ActorId actor, UUID id) {
        return jdbc.sql("""
                SELECT a.chart::text AS chart FROM chat_file_artifact a
                JOIN chat_message m ON m.id = a.message_id
                JOIN chat_session s ON s.id = m.session_id AND s.tenant_id = a.tenant_id
                WHERE a.tenant_id = :tenant AND a.id = :id AND s.owner_actor_id = :actor AND s.deleted_at IS NULL
                  AND a.deleted_at IS NULL AND a.chart IS NOT NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .query((row, ignored) -> row.getString("chart")).optional();
    }

    /** Serving lookup: the actor must own the chat that produced the file. */
    public Optional<InterpreterArtifact> ownedArtifact(TenantId tenant, ActorId actor, UUID id) {
        return jdbc.sql("""
                SELECT a.object_key, a.filename, a.media_type, a.preview_object_key FROM chat_file_artifact a
                JOIN chat_message m ON m.id = a.message_id
                JOIN chat_session s ON s.id = m.session_id AND s.tenant_id = a.tenant_id
                WHERE a.tenant_id = :tenant AND a.id = :id AND s.owner_actor_id = :actor AND s.deleted_at IS NULL
                  AND a.deleted_at IS NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .query((row, ignored) -> new InterpreterArtifact(new ObjectKey(row.getString("object_key")),
                        row.getString("filename"), row.getString("media_type"),
                        row.getString("preview_object_key") == null ? null : new ObjectKey(row.getString("preview_object_key"))))
                .optional();
    }

    /**
     * Records a converted preview once; returns false when another request already recorded one, or when the
     * file was deleted while the conversion ran, so the caller discards the object it staged.
     */
    public boolean attachPreview(TenantId tenant, UUID id, UUID storedObjectId, ObjectKey key, long sizeBytes) {
        return jdbc.sql("""
                UPDATE chat_file_artifact SET preview_stored_object_id = :object, preview_object_key = :key,
                    preview_size_bytes = :size
                WHERE tenant_id = :tenant AND id = :id AND preview_object_key IS NULL AND deleted_at IS NULL
                """).param("tenant", tenant.value()).param("id", id).param("object", storedObjectId)
                .param("key", key.value()).param("size", sizeBytes).update() == 1;
    }
}
