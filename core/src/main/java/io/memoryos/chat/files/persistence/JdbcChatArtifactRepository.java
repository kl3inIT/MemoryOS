package io.memoryos.chat.files.persistence;

import io.memoryos.library.ChatLibraryFile;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The generated files and images of Chat's answers as the file library and a branch see them: a rename or a star
 * the library asks for, and the artifacts of one answer that a branch copies with it.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChatArtifactRepository {
    private final JdbcClient jdbc;

    public JdbcChatArtifactRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * Renames or stars a generated file or image the owner's library lists: not deleted, in a conversation that is
     * not deleted. Answers false when there is no such artifact.
     */
    public boolean update(TenantId tenant, ActorId actor, ChatLibraryFile.Source source, UUID id,
                          @Nullable String filename, @Nullable Boolean favorite) {
        String table = table(source);
        return jdbc.sql("""
                UPDATE %s f SET filename = COALESCE(CAST(:filename AS varchar), f.filename),
                    favorite_at = CASE WHEN CAST(:favorite AS boolean) IS NULL THEN f.favorite_at
                                       WHEN CAST(:favorite AS boolean) THEN COALESCE(f.favorite_at, CURRENT_TIMESTAMP)
                                       ELSE NULL END
                WHERE f.tenant_id = :tenant AND f.id = :id AND f.owner_actor_id = :actor AND f.deleted_at IS NULL
                  AND EXISTS (SELECT 1 FROM chat_session s WHERE s.id = f.session_id
                              AND s.tenant_id = f.tenant_id AND s.deleted_at IS NULL)
                """.formatted(table)).param("tenant", tenant.value()).param("actor", actor.value())
                .param("id", id).param("filename", filename).param("favorite", favorite).update() == 1;
    }

    private static String table(ChatLibraryFile.Source source) {
        return switch (source) {
            case GENERATED -> "chat_file_artifact";
            case IMAGE -> "chat_image_artifact";
            case UPLOAD -> throw new IllegalArgumentException("an upload is not an artifact");
            // A meeting has no artifact table of its own: it is published straight into the library as an upload.
            case MEETING -> throw new IllegalArgumentException("a meeting is published, not listed as an artifact");
        };
    }

    /**
     * A generated file or image recorded against one answer, with what a copy of it needs (MEM-153). The preview
     * rendering and the lineage of an edited image are left out: a preview is derived again on demand, and
     * lineage names an artifact of the conversation that is being branched from.
     */
    public record MessageArtifact(ChatLibraryFile.Source source, UUID id, ObjectKey key, String filename,
                                  String mediaType, long sizeBytes, @Nullable String chart,
                                  @Nullable String revisedPrompt) {}

    /** Every artifact of one answer, generated files first, each in the order it was recorded. */
    public List<MessageArtifact> messageArtifacts(TenantId tenant, UUID messageId) {
        var files = jdbc.sql("""
                SELECT a.id, a.object_key, a.filename, a.media_type, a.size_bytes, a.chart::text AS chart
                FROM chat_file_artifact a
                WHERE a.tenant_id = :tenant AND a.message_id = :message AND a.deleted_at IS NULL
                ORDER BY a.created_at, a.id
                """).param("tenant", tenant.value()).param("message", messageId)
                .query((row, ignored) -> new MessageArtifact(ChatLibraryFile.Source.GENERATED,
                        row.getObject("id", UUID.class), new ObjectKey(row.getString("object_key")),
                        row.getString("filename"), row.getString("media_type"), row.getLong("size_bytes"),
                        row.getString("chart"), null))
                .list();
        var images = jdbc.sql("""
                SELECT a.id, a.object_key, a.filename, a.media_type, a.size_bytes, a.revised_prompt
                FROM chat_image_artifact a
                WHERE a.tenant_id = :tenant AND a.message_id = :message AND a.deleted_at IS NULL
                ORDER BY a.created_at, a.id
                """).param("tenant", tenant.value()).param("message", messageId)
                .query((row, ignored) -> new MessageArtifact(ChatLibraryFile.Source.IMAGE,
                        row.getObject("id", UUID.class), new ObjectKey(row.getString("object_key")),
                        row.getString("filename"), row.getString("media_type"), row.getLong("size_bytes"),
                        null, row.getString("revised_prompt")))
                .list();
        var all = new ArrayList<MessageArtifact>(files.size() + images.size());
        all.addAll(files);
        all.addAll(images);
        return all;
    }

    /**
     * Records a copy of one artifact against a copied answer, over bytes written separately. Owner and
     * conversation are read from the answer itself, exactly as when the artifact was first recorded, so a copy
     * cannot be attributed to anyone else.
     */
    public void copyArtifact(TenantId tenant, MessageArtifact artifact, UUID copyId, UUID messageId,
                             UUID storedObjectId, ObjectKey key) {
        String statement = artifact.source() == ChatLibraryFile.Source.GENERATED ? """
                INSERT INTO chat_file_artifact(id, tenant_id, message_id, stored_object_id, object_key, filename,
                                               media_type, size_bytes, chart, owner_actor_id, session_id)
                SELECT :id, :tenant, :message, :object, :key, :filename, :type, :size, CAST(:extra AS jsonb),
                       s.owner_actor_id, s.id
                FROM chat_message m JOIN chat_session s ON s.id = m.session_id
                WHERE m.id = :message AND s.tenant_id = :tenant
                """ : """
                INSERT INTO chat_image_artifact(id, tenant_id, message_id, stored_object_id, object_key, filename,
                                                media_type, size_bytes, revised_prompt, owner_actor_id, session_id)
                SELECT :id, :tenant, :message, :object, :key, :filename, :type, :size, :extra,
                       s.owner_actor_id, s.id
                FROM chat_message m JOIN chat_session s ON s.id = m.session_id
                WHERE m.id = :message AND s.tenant_id = :tenant
                """;
        int inserted = jdbc.sql(statement).param("id", copyId).param("tenant", tenant.value())
                .param("message", messageId).param("object", storedObjectId).param("key", key.value())
                .param("filename", artifact.filename()).param("type", artifact.mediaType())
                .param("size", artifact.sizeBytes())
                .param("extra", artifact.source() == ChatLibraryFile.Source.GENERATED ? artifact.chart()
                        : artifact.revisedPrompt())
                .update();
        if (inserted != 1) throw new IllegalStateException("copied artifact has no answer in this tenant");
    }

}
