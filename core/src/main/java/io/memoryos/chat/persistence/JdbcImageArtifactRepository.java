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

    public record Artifact(UUID id, String mediaType, @Nullable String revisedPrompt) {}
    public record Content(ObjectKey key, String mediaType) {}

    public void insert(TenantId tenant, UUID messageId, UUID id, UUID storedObjectId, ObjectKey key,
                       String mediaType, @Nullable String revisedPrompt) {
        jdbc.sql("""
                INSERT INTO chat_image_artifact(id,tenant_id,message_id,stored_object_id,object_key,media_type,revised_prompt)
                VALUES(:id,:tenant,:message,:object,:key,:type,:revised)
                """).param("id", id).param("tenant", tenant.value()).param("message", messageId)
                .param("object", storedObjectId).param("key", key.value()).param("type", mediaType)
                .param("revised", revisedPrompt).update();
    }

    public Map<UUID, List<Artifact>> byMessages(TenantId tenant, Collection<UUID> messageIds) {
        if (messageIds.isEmpty()) return Map.of();
        var result = new LinkedHashMap<UUID, List<Artifact>>();
        jdbc.sql("""
                SELECT message_id, id, media_type, revised_prompt FROM chat_image_artifact
                WHERE tenant_id = :tenant AND message_id IN (:messages) ORDER BY created_at, id
                """).param("tenant", tenant.value()).param("messages", messageIds)
                .query((row, ignored) -> {
                    result.computeIfAbsent(row.getObject("message_id", UUID.class), key -> new ArrayList<>())
                            .add(new Artifact(row.getObject("id", UUID.class), row.getString("media_type"), row.getString("revised_prompt")));
                    return true;
                }).list();
        return result;
    }

    public List<Artifact> byMessage(TenantId tenant, UUID messageId) {
        return jdbc.sql("""
                SELECT id, media_type, revised_prompt FROM chat_image_artifact
                WHERE tenant_id = :tenant AND message_id = :message ORDER BY created_at, id
                """).param("tenant", tenant.value()).param("message", messageId)
                .query((row, ignored) -> new Artifact(row.getObject("id", UUID.class), row.getString("media_type"), row.getString("revised_prompt")))
                .list();
    }

    /** Serving lookup: the actor must own the chat that produced the artifact. */
    public Optional<Content> owned(TenantId tenant, ActorId actor, UUID id) {
        return jdbc.sql("""
                SELECT a.object_key, a.media_type FROM chat_image_artifact a
                JOIN chat_message m ON m.id = a.message_id
                JOIN chat_session s ON s.id = m.session_id AND s.tenant_id = a.tenant_id
                WHERE a.tenant_id = :tenant AND a.id = :id AND s.owner_actor_id = :actor AND s.deleted_at IS NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .query((row, ignored) -> new Content(new ObjectKey(row.getString("object_key")), row.getString("media_type")))
                .optional();
    }
}
