package io.memoryos.chat.interpreter;

import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.objectstorage.ObjectKey;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcInterpreterRepository {
    private final JdbcClient jdbc;

    public JdbcInterpreterRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Setting(boolean enabled, long revision) {}
    public record Artifact(ObjectKey key, String filename, String mediaType) {}

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

    public void insertArtifact(TenantId tenant, UUID messageId, UUID id, UUID storedObjectId, ObjectKey key,
                               String filename, String mediaType, long sizeBytes) {
        jdbc.sql("""
                INSERT INTO chat_file_artifact(id, tenant_id, message_id, stored_object_id, object_key, filename, media_type, size_bytes)
                VALUES(:id, :tenant, :message, :object, :key, :filename, :type, :size)
                """).param("id", id).param("tenant", tenant.value()).param("message", messageId).param("object", storedObjectId)
                .param("key", key.value()).param("filename", filename).param("type", mediaType).param("size", sizeBytes).update();
    }

    /** Serving lookup: the actor must own the chat that produced the file. */
    public Optional<Artifact> ownedArtifact(TenantId tenant, ActorId actor, UUID id) {
        return jdbc.sql("""
                SELECT a.object_key, a.filename, a.media_type FROM chat_file_artifact a
                JOIN chat_message m ON m.id = a.message_id
                JOIN chat_session s ON s.id = m.session_id AND s.tenant_id = a.tenant_id
                WHERE a.tenant_id = :tenant AND a.id = :id AND s.owner_actor_id = :actor AND s.deleted_at IS NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .query((row, ignored) -> new Artifact(new ObjectKey(row.getString("object_key")),
                        row.getString("filename"), row.getString("media_type"))).optional();
    }
}
