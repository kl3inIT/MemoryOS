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
    /** {@code chart} says chart data is stored beside the PNG; it is read separately to keep lists small. */
    public record GeneratedFile(UUID id, String filename, String mediaType, long sizeBytes, boolean chart) {}

    /** Generated files of an already-authorized page of messages, keyed by message id. */
    public java.util.Map<UUID, java.util.List<GeneratedFile>> byMessages(TenantId tenant, java.util.Collection<UUID> messageIds) {
        if (messageIds.isEmpty()) return java.util.Map.of();
        var result = new java.util.LinkedHashMap<UUID, java.util.List<GeneratedFile>>();
        jdbc.sql("""
                SELECT message_id, id, filename, media_type, size_bytes, chart IS NOT NULL AS has_chart FROM chat_file_artifact
                WHERE tenant_id = :tenant AND message_id IN (:messages) ORDER BY created_at, id
                """).param("tenant", tenant.value()).param("messages", messageIds)
                .query((row, ignored) -> {
                    result.computeIfAbsent(row.getObject("message_id", UUID.class), key -> new java.util.ArrayList<>())
                            .add(new GeneratedFile(row.getObject("id", UUID.class), row.getString("filename"),
                                    row.getString("media_type"), row.getLong("size_bytes"), row.getBoolean("has_chart")));
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

    public void insertArtifact(TenantId tenant, UUID messageId, UUID id, UUID storedObjectId, ObjectKey key,
                               String filename, String mediaType, long sizeBytes, @org.jspecify.annotations.Nullable String chart) {
        jdbc.sql("""
                INSERT INTO chat_file_artifact(id, tenant_id, message_id, stored_object_id, object_key, filename, media_type, size_bytes, chart)
                VALUES(:id, :tenant, :message, :object, :key, :filename, :type, :size, CAST(:chart AS jsonb))
                """).param("id", id).param("tenant", tenant.value()).param("message", messageId).param("object", storedObjectId)
                .param("key", key.value()).param("filename", filename).param("type", mediaType).param("size", sizeBytes)
                .param("chart", chart).update();
    }

    /** Chart data of a generated file the actor owns, as JSON text. */
    public Optional<String> ownedChart(TenantId tenant, ActorId actor, UUID id) {
        return jdbc.sql("""
                SELECT a.chart::text AS chart FROM chat_file_artifact a
                JOIN chat_message m ON m.id = a.message_id
                JOIN chat_session s ON s.id = m.session_id AND s.tenant_id = a.tenant_id
                WHERE a.tenant_id = :tenant AND a.id = :id AND s.owner_actor_id = :actor AND s.deleted_at IS NULL
                  AND a.chart IS NOT NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .query((row, ignored) -> row.getString("chart")).optional();
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
