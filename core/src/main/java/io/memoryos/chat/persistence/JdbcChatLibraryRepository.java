package io.memoryos.chat.persistence;

import io.memoryos.chat.ChatLibraryFile;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.objectstorage.ObjectKey;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The owner's files across the three places Chat keeps them: uploads, files {@code run_python} generated and
 * generated images. One statement unions the three, because a page must sort and count over all of them, and
 * the window totals are computed before {@code LIMIT} so the filtered size never needs a second query.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChatLibraryRepository {
    private final JdbcClient jdbc;

    public JdbcChatLibraryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Page(List<ChatLibraryFile> items, long totalCount, long totalBytes) {}

    /**
     * A category is derived from the stored media type and name rather than stored, so it cannot drift from
     * the file and a new type needs no migration.
     */
    private static final String CATEGORY = """
            CASE
                WHEN f.media_type LIKE 'image/%' THEN 'IMAGE'
                WHEN f.media_type IN ('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
                                      'application/vnd.ms-excel','text/csv','text/tab-separated-values')
                     OR lower(f.filename) ~ '\\.(xlsx|xlsm|xls|csv|tsv)$' THEN 'SPREADSHEET'
                WHEN f.media_type IN ('application/vnd.openxmlformats-officedocument.presentationml.presentation',
                                      'application/vnd.ms-powerpoint')
                     OR lower(f.filename) ~ '\\.(pptx|ppt)$' THEN 'PRESENTATION'
                WHEN f.media_type IN ('application/pdf','application/msword','text/markdown','text/plain',
                                      'application/vnd.openxmlformats-officedocument.wordprocessingml.document')
                     OR lower(f.filename) ~ '\\.(pdf|docx|doc|md|txt|rtf|odt)$' THEN 'DOCUMENT'
                ELSE 'OTHER'
            END""";

    /**
     * Uploads carry no conversation: they may be attached to several, to a Project or Agent, or to none. Only a
     * READY upload is listed, because the library previews and downloads a file, and those routes serve no other
     * status; an upload still being processed or failed stays in the composer's own recent list.
     *
     * <p>A conversation's own files (MEM-144) are its artifacts plus the uploads attached in it, which are read
     * from that one conversation's message descriptors. The join on the owner keeps a foreign conversation from
     * revealing where an Agent-attached upload was used.
     */
    private static final String SOURCES = """
            SELECT 'UPLOAD' AS source, u.id, u.filename,
                   COALESCE(u.detected_media_type, u.media_type) AS media_type, u.size_bytes, u.created_at,
                   NULL::uuid AS session_id, NULL::varchar AS session_title, NULL::text AS search_extra,
                   CASE WHEN :allSessions THEN NULL ELSE (
                        SELECT m.id FROM chat_message m CROSS JOIN LATERAL jsonb_array_elements(m.files) descriptor
                        WHERE m.session_id = :session AND CAST(descriptor ->> 'id' AS uuid) = u.id
                        ORDER BY m.created_at, m.id LIMIT 1) END AS message_id
            FROM chat_user_file u
            WHERE u.tenant_id = :tenant AND u.owner_actor_id = :actor AND u.status = 'READY'
              AND (:allSessions OR EXISTS (
                    SELECT 1 FROM chat_message m
                    JOIN chat_session cs ON cs.id = m.session_id AND cs.tenant_id = u.tenant_id
                        AND cs.owner_actor_id = :actor AND cs.deleted_at IS NULL
                    CROSS JOIN LATERAL jsonb_array_elements(m.files) descriptor
                    WHERE m.session_id = :session AND CAST(descriptor ->> 'id' AS uuid) = u.id))
            UNION ALL
            SELECT 'GENERATED', a.id, a.filename, a.media_type, a.size_bytes, a.created_at, s.id, s.title, NULL, a.message_id
            FROM chat_file_artifact a JOIN chat_session s ON s.id = a.session_id AND s.tenant_id = a.tenant_id
            WHERE a.tenant_id = :tenant AND a.owner_actor_id = :actor AND a.deleted_at IS NULL AND s.deleted_at IS NULL
              AND (:allSessions OR a.session_id = :session)
            UNION ALL
            SELECT 'IMAGE', a.id, a.filename, a.media_type, a.size_bytes, a.created_at, s.id, s.title, a.revised_prompt,
                   a.message_id
            FROM chat_image_artifact a JOIN chat_session s ON s.id = a.session_id AND s.tenant_id = a.tenant_id
            WHERE a.tenant_id = :tenant AND a.owner_actor_id = :actor AND a.deleted_at IS NULL AND s.deleted_at IS NULL
              AND (:allSessions OR a.session_id = :session)
            """;

    /** The filtered rows, without the paging and the windows, so the page and the totals share one definition. */
    private static final String FILTERED = """
            SELECT f.*, %s AS category FROM (%s) f
            WHERE (:query = '' OR f.filename ILIKE :pattern OR (f.search_extra IS NOT NULL AND f.search_extra ILIKE :pattern))
              AND (:allSources OR f.source IN (:sources))
            """;

    public Page page(TenantId tenant, ActorId actor, String query, Set<String> sources, Set<String> categories,
                     @Nullable UUID session, ChatLibraryFile.Sort sort, int offset, int limit) {
        String order = switch (sort) {
            case NEWEST -> "created_at DESC, id";
            case OLDEST -> "created_at, id";
            case LARGEST -> "size_bytes DESC, created_at DESC, id";
            case SMALLEST -> "size_bytes, created_at DESC, id";
        };
        var rows = bind(jdbc.sql("""
                SELECT source, id, filename, media_type, size_bytes, created_at, session_id, session_title, category,
                       message_id, COUNT(*) OVER () AS total_count, COALESCE(SUM(size_bytes) OVER (), 0) AS total_bytes
                FROM (%s) categorized
                WHERE :allCategories OR category IN (:categories)
                ORDER BY %s OFFSET :offset LIMIT :limit
                """.formatted(filtered(), order)), tenant, actor, query, sources, categories, session)
                .param("offset", offset).param("limit", limit)
                .query((row, ignored) -> new Row(new ChatLibraryFile(
                        ChatLibraryFile.Source.valueOf(row.getString("source")), row.getObject("id", UUID.class),
                        row.getString("filename"), row.getString("media_type"), row.getLong("size_bytes"),
                        row.getTimestamp("created_at").toInstant(), ChatLibraryFile.Category.valueOf(row.getString("category")),
                        row.getObject("session_id", UUID.class), row.getString("session_title"),
                        row.getObject("message_id", UUID.class), List.of()),
                        row.getLong("total_count"), row.getLong("total_bytes")))
                .list();
        // A page past the end carries no window row, and the filter's totals must not collapse with it.
        if (rows.isEmpty()) return totals(tenant, actor, query, sources, categories, session);
        return new Page(rows.stream().map(Row::file).toList(),
                rows.getFirst().totalCount(), rows.getFirst().totalBytes());
    }

    private Page totals(TenantId tenant, ActorId actor, String query, Set<String> sources, Set<String> categories,
                        @Nullable UUID session) {
        return bind(jdbc.sql("""
                SELECT count(*) AS total_count, COALESCE(SUM(size_bytes), 0) AS total_bytes
                FROM (%s) categorized
                WHERE :allCategories OR category IN (:categories)
                """.formatted(filtered())), tenant, actor, query, sources, categories, session)
                .query((row, ignored) -> new Page(List.of(), row.getLong("total_count"), row.getLong("total_bytes")))
                .single();
    }

    private static String filtered() { return FILTERED.formatted(CATEGORY, SOURCES); }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, TenantId tenant, ActorId actor,
                                                 String query, Set<String> sources, Set<String> categories,
                                                 @Nullable UUID session) {
        return statement.param("tenant", tenant.value()).param("actor", actor.value())
                .param("allSessions", session == null).param("session", session)
                .param("query", query).param("pattern", "%" + escape(query) + "%")
                .param("allSources", sources.isEmpty()).param("sources", sources.isEmpty() ? Set.of("") : sources)
                .param("allCategories", categories.isEmpty()).param("categories", categories.isEmpty() ? Set.of("") : categories);
    }

    private record Row(ChatLibraryFile file, long totalCount, long totalBytes) {}

    /** The stored bytes of an artifact the caller may copy into an upload. */
    public record Artifact(ObjectKey key, String filename, String mediaType, long sizeBytes) {}

    /**
     * An artifact the actor owns, in a conversation that is not deleted, and not deleted itself: exactly what the
     * library lists, so a file can be copied only while the library shows it.
     */
    public Optional<Artifact> artifact(TenantId tenant, ActorId actor, ChatLibraryFile.Source source, UUID id) {
        String table = switch (source) {
            case GENERATED -> "chat_file_artifact";
            case IMAGE -> "chat_image_artifact";
            case UPLOAD -> throw new IllegalArgumentException("an upload is not an artifact");
        };
        return jdbc.sql("""
                SELECT a.object_key, a.filename, a.media_type, a.size_bytes FROM %s a
                JOIN chat_session s ON s.id = a.session_id AND s.tenant_id = a.tenant_id
                WHERE a.tenant_id = :tenant AND a.id = :id AND a.owner_actor_id = :actor
                  AND a.deleted_at IS NULL AND s.deleted_at IS NULL
                """.formatted(table)).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .query((row, ignored) -> new Artifact(new ObjectKey(row.getString("object_key")), row.getString("filename"),
                        row.getString("media_type"), row.getLong("size_bytes")))
                .optional();
    }

    /** ILIKE treats these as wildcards; a name search must match them literally. */
    private static String escape(String query) {
        return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

}
