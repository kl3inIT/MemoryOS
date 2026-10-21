package io.memoryos.chat.files.persistence;

import io.memoryos.chat.persona.persistence.AgentAccessSql;
import io.memoryos.library.LibraryFile;
import io.memoryos.library.FileAttachments;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

    /**
     * An agent a public bot answers with, in a conversation the actor holds through that bot: the group's own live
     * assistant, else the bot's, as Chat runs the conversation's turns (MEM-273). It grants its knowledge files to the
     * conversation's owner as it grants its instructions, so a public bot answers from the files of an assistant its
     * account may not use elsewhere (MEM-298). Only a public bot's conversation counts: it runs as the bot's service
     * account, which nobody signs in as, so the grant never reaches a person's library or downloads.
     */
    private static final String HELD_IN_CHANNEL = """
            EXISTS (SELECT 1 FROM chat_session s
                    WHERE s.tenant_id = p.tenant_id AND s.owner_actor_id = :actor AND s.surface = 'CHANNEL'
                      AND s.channel_public AND s.deleted_at IS NULL AND s.persona_id = p.id
                      AND p.id = COALESCE(
                          (SELECT group_binding.persona_id FROM chat_channel_group_binding group_binding
                           JOIN persona group_persona ON group_persona.tenant_id = group_binding.tenant_id
                               AND group_persona.id = group_binding.persona_id AND group_persona.deleted_at IS NULL
                           WHERE group_binding.tenant_id = s.tenant_id AND group_binding.connection_id = s.channel_connection_id
                             AND group_binding.external_chat_id = s.channel_chat_id),
                          (SELECT channel_binding.persona_id FROM chat_channel_binding channel_binding
                           WHERE channel_binding.tenant_id = s.tenant_id
                             AND channel_binding.connection_id = s.channel_connection_id)))""";

    /**
     * The files among {@code files} that a non-deleted agent the actor can use attaches or shows as its avatar, and the
     * knowledge files of an agent a public bot answers with in a conversation the actor holds through it.
     */
    public Set<UUID> usableThroughAgents(TenantId tenant, ActorId actor, Collection<UUID> files) {
        if (files.isEmpty()) return Set.of();
        // Agent managers get no extra file authority here.
        String uses = AgentAccessSql.USES.replace(":agentsManage", "FALSE");
        var found = jdbc.sql("""
                SELECT CAST(attached.file_id AS uuid) AS file_id FROM persona p
                    CROSS JOIN LATERAL jsonb_array_elements_text(p.file_ids) AS attached(file_id)
                WHERE p.tenant_id = :tenant AND p.deleted_at IS NULL AND attached.file_id IN (:texts) AND (%s OR %s)
                UNION
                SELECT p.avatar_file_id FROM persona p
                WHERE p.tenant_id = :tenant AND p.deleted_at IS NULL AND p.avatar_file_id IN (:ids) AND %s
                """.formatted(uses, HELD_IN_CHANNEL, uses)).param("tenant", tenant.value()).param("actor", actor.value())
                .param("texts", texts(files)).param("ids", files)
                .query((row, ignored) -> row.getObject("file_id", UUID.class)).list();
        return Set.copyOf(new HashSet<>(found));
    }

    /**
     * The knowledge files of non-deleted agents the actor can use, one row per agent and file, for at most
     * {@code limit} files; {@code files} narrows them when given. The same rule as {@link #usableThroughAgents}
     * without the avatar branch: an avatar is the agent's icon, not a file it hands its users.
     */
    public List<FileAttachments.AgentFile> agentFiles(TenantId tenant, ActorId actor, @Nullable Collection<UUID> files,
                                                      int limit) {
        if (files != null && files.isEmpty()) return List.of();
        String uses = AgentAccessSql.USES.replace(":agentsManage", "FALSE");
        return jdbc.sql("""
                WITH granted AS (
                    SELECT DISTINCT CAST(attached.file_id AS uuid) AS file_id, p.id AS agent_id, p.name
                    FROM persona p CROSS JOIN LATERAL jsonb_array_elements_text(p.file_ids) AS attached(file_id)
                    WHERE p.tenant_id = :tenant AND p.deleted_at IS NULL
                      AND (:allFiles OR attached.file_id IN (:texts)) AND %s
                ), bounded AS (
                    SELECT DISTINCT file_id FROM granted ORDER BY file_id LIMIT :limit
                )
                SELECT g.file_id, g.agent_id, g.name FROM granted g JOIN bounded b ON b.file_id = g.file_id
                ORDER BY g.file_id, g.name, g.agent_id
                """.formatted(uses)).param("tenant", tenant.value()).param("actor", actor.value())
                .param("allFiles", files == null).param("texts", files == null ? List.of("") : texts(files))
                .param("limit", limit)
                .query((row, ignored) -> new FileAttachments.AgentFile(row.getObject("file_id", UUID.class),
                        row.getObject("agent_id", UUID.class), row.getString("name")))
                .list();
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
                        LibraryFile.Usage.Kind.valueOf(row.getString("kind")), row.getObject("id", UUID.class),
                        row.getString("name")))
                .list();
    }

    /** {@code file_ids} holds each id as its canonical lower-case text, which is what {@link UUID#toString()} gives. */
    private static List<String> texts(Collection<UUID> files) {
        return files.stream().map(UUID::toString).toList();
    }
}
