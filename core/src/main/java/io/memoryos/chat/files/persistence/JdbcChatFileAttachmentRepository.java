package io.memoryos.chat.files.persistence;

import io.memoryos.chat.persona.persistence.AgentAccessSql;
import io.memoryos.library.ChatLibraryFile;
import io.memoryos.library.FileAttachments;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The agents and Projects that attach library uploads. File ids are compared as the text Chat stores them as in
 * {@code file_ids}; the library's own rows are not read here.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChatFileAttachmentRepository {
    private final JdbcClient jdbc;

    public JdbcChatFileAttachmentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** The files among {@code files} that a non-deleted agent the actor can use attaches or shows as its avatar. */
    public Set<UUID> usableThroughAgents(TenantId tenant, ActorId actor, Collection<UUID> files) {
        if (files.isEmpty()) return Set.of();
        // Agent managers get no extra file authority here.
        String uses = AgentAccessSql.USES.replace(":agentsManage", "FALSE");
        var found = jdbc.sql("""
                SELECT CAST(attached.file_id AS uuid) AS file_id FROM persona p
                    CROSS JOIN LATERAL jsonb_array_elements_text(p.file_ids) AS attached(file_id)
                WHERE p.tenant_id = :tenant AND p.deleted_at IS NULL AND attached.file_id IN (:texts) AND %s
                UNION
                SELECT p.avatar_file_id FROM persona p
                WHERE p.tenant_id = :tenant AND p.deleted_at IS NULL AND p.avatar_file_id IN (:ids) AND %s
                """.formatted(uses, uses)).param("tenant", tenant.value()).param("actor", actor.value())
                .param("texts", texts(files)).param("ids", files)
                .query((row, ignored) -> row.getObject("file_id", UUID.class)).list();
        return Set.copyOf(new HashSet<>(found));
    }

    /** What attaches each of {@code files}, as the library labels it; ordered by kind and name. */
    public List<FileAttachments.Holder> holders(TenantId tenant, Collection<UUID> files) {
        if (files.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT file_id, kind, id, name FROM (
                    SELECT CAST(attached.file_id AS uuid) AS file_id, 'AGENT' AS kind, p.id, p.name FROM persona p
                        CROSS JOIN LATERAL jsonb_array_elements_text(p.file_ids) AS attached(file_id)
                    WHERE p.tenant_id = :tenant AND p.deleted_at IS NULL AND attached.file_id IN (:texts)
                    UNION
                    SELECT p.avatar_file_id, 'AGENT', p.id, p.name FROM persona p
                    WHERE p.tenant_id = :tenant AND p.deleted_at IS NULL AND p.avatar_file_id IN (:ids)
                    UNION
                    SELECT CAST(attached.file_id AS uuid), 'PROJECT', c.id, c.name FROM chat_project c
                        CROSS JOIN LATERAL jsonb_array_elements_text(c.file_ids) AS attached(file_id)
                    WHERE c.tenant_id = :tenant AND attached.file_id IN (:texts)
                ) holder
                ORDER BY kind, name
                """).param("tenant", tenant.value()).param("texts", texts(files)).param("ids", files)
                .query((row, ignored) -> new FileAttachments.Holder(row.getObject("file_id", UUID.class),
                        ChatLibraryFile.Usage.Kind.valueOf(row.getString("kind")), row.getObject("id", UUID.class),
                        row.getString("name")))
                .list();
    }

    /** {@code file_ids} holds each id as its canonical lower-case text, which is what {@link UUID#toString()} gives. */
    private static List<String> texts(Collection<UUID> files) {
        return files.stream().map(UUID::toString).toList();
    }
}
