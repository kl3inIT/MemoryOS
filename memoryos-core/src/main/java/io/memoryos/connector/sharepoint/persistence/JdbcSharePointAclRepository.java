package io.memoryos.connector.sharepoint.persistence;

import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.SourceAclChanged;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.sync.persistence.WorkLeases;
import io.memoryos.document.DocumentId;
import io.memoryos.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Who SharePoint lets read each item of a SYNC Source, as its last complete read (MEM-136). The grants a reader is
 * checked against are derived from these rows by {@code JdbcSourceDocumentRepository}.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSharePointAclRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public JdbcSharePointAclRepository(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * How the Source reads SharePoint's permissions, or empty when it does not admit readers by them, which is when
     * they are not read at all.
     */
    public Optional<Sharing> sharing(Work work) {
        return jdbc.sql("""
                SELECT s.treat_sharing_links_as_public, s.next_permission_sync_at <= CURRENT_TIMESTAMP AS due
                FROM sharepoint_sources s
                JOIN connector_credential_pairs p ON p.tenant_id = s.tenant_id AND p.id = s.source_id
                WHERE s.tenant_id = :tenant AND s.source_id = :source AND p.access_type = 'SYNC'
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .query((r, _) -> new Sharing(r.getBoolean("treat_sharing_links_as_public"), r.getBoolean("due")))
                .optional();
    }

    /** The item as SharePoint keeps it, when the Source still holds it. */
    public Optional<Item> item(Work work, String fileId) {
        return jdbc.sql("""
                SELECT provider_file_id, kind, drive_id, path, list_id, list_item_id, web_url FROM sharepoint_items
                WHERE tenant_id = :tenant AND source_id = :source AND provider_file_id = :file
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("file", fileId).query(JdbcSharePointAclRepository::item).optional();
    }

    /**
     * The next items of the Source after {@code afterFileId}, in the order the permission read walks them. An item
     * whose permissions were asked for since {@code since}, which is when the run started, is not asked again.
     */
    public List<Item> items(Work work, @Nullable String afterFileId, Instant since, int limit) {
        return jdbc.sql("""
                SELECT i.provider_file_id, i.kind, i.drive_id, i.path, i.list_id, i.list_item_id, i.web_url
                FROM sharepoint_items i
                WHERE i.tenant_id = :tenant AND i.source_id = :source
                  AND (CAST(:after AS VARCHAR) IS NULL OR i.provider_file_id > :after)
                  AND NOT EXISTS (SELECT 1 FROM sharepoint_acl_snapshots a
                      WHERE a.tenant_id = i.tenant_id AND a.source_id = i.source_id
                        AND a.file_id = i.provider_file_id AND a.last_attempt_at >= :since)
                ORDER BY i.provider_file_id LIMIT :limit
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("after", afterFileId).param("since", WorkLeases.sqlTime(since)).param("limit", limit)
                .query(JdbcSharePointAclRepository::item).list();
    }

    /** Records where SharePoint keeps an item that was acquired before that was remembered. */
    public void located(Work work, String fileId, @Nullable String listId, @Nullable String listItemId,
            String webUrl) {
        jdbc.sql("""
                UPDATE sharepoint_items SET list_id = :list, list_item_id = :row, web_url = :web
                WHERE tenant_id = :tenant AND source_id = :source AND provider_file_id = :file
                """).param("list", listId).param("row", listItemId).param("web", webUrl)
                .param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("file", fileId).update();
    }

    /** Asks for the permissions of every item to be read by the Source's next run. */
    public void permissionsDue(TenantId tenant, SourceId source) {
        jdbc.sql("""
                UPDATE sharepoint_sources SET next_permission_sync_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    /** The read of every item finished: the next one is {@code interval} away. */
    public void permissionsRead(Work work, Duration interval) {
        jdbc.sql("""
                UPDATE sharepoint_sources SET next_permission_sync_at = CURRENT_TIMESTAMP + :seconds * INTERVAL '1 second'
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("seconds", interval.toSeconds()).param("tenant", work.tenantId().value())
                .param("source", work.sourceId().value()).update();
    }

    /** Caller holds the Source's fences in its transaction. */
    public void recordSuccess(Work work, String fileId, boolean everyone, Collection<String> userEmails,
            Collection<String> groupIds) {
        record(work, fileId, everyone, JSON.writeValueAsString(new TreeSet<>(userEmails)),
                JSON.writeValueAsString(new TreeSet<>(groupIds)), null);
    }

    /** Keeps the last complete answer in force, independently of this failed attempt. */
    public void recordFailure(Work work, String fileId, String errorCode) {
        record(work, fileId, false, null, null, WorkLeases.safeErrorCode(errorCode));
    }

    private void record(Work work, String fileId, boolean everyone, @Nullable String emails, @Nullable String groups,
            @Nullable String errorCode) {
        boolean success = emails != null;
        var row = jdbc.sql("""
                WITH prior AS (
                    SELECT access_public, user_emails, group_ids FROM sharepoint_acl_snapshots
                    WHERE tenant_id = :tenant AND source_id = :source AND file_id = :file
                ), upserted AS (
                    INSERT INTO sharepoint_acl_snapshots (tenant_id, source_id, file_id, observation_revision,
                        access_public, user_emails, group_ids, status, last_attempt_at, last_success_at, error_code)
                    VALUES (:tenant, :source, :file, CASE WHEN :success THEN 1 ELSE 0 END, :everyone,
                        CAST(:emails AS jsonb), CAST(:groups AS jsonb), :status, statement_timestamp(),
                        CASE WHEN :success THEN statement_timestamp() END, :error)
                    ON CONFLICT (tenant_id, source_id, file_id) DO UPDATE SET
                        observation_revision = sharepoint_acl_snapshots.observation_revision
                            + EXCLUDED.observation_revision,
                        access_public = CASE WHEN :success THEN EXCLUDED.access_public
                            ELSE sharepoint_acl_snapshots.access_public END,
                        user_emails = COALESCE(EXCLUDED.user_emails, sharepoint_acl_snapshots.user_emails),
                        group_ids = COALESCE(EXCLUDED.group_ids, sharepoint_acl_snapshots.group_ids),
                        status = EXCLUDED.status, last_attempt_at = EXCLUDED.last_attempt_at,
                        last_success_at = COALESCE(EXCLUDED.last_success_at, sharepoint_acl_snapshots.last_success_at),
                        error_code = EXCLUDED.error_code
                    RETURNING observation_revision, access_public, user_emails, group_ids
                )
                SELECT u.observation_revision,
                    u.access_public IS DISTINCT FROM (SELECT prior.access_public FROM prior)
                        OR u.user_emails IS DISTINCT FROM (SELECT prior.user_emails FROM prior)
                        OR u.group_ids IS DISTINCT FROM (SELECT prior.group_ids FROM prior) AS changed
                FROM upserted u
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("file", fileId).param("success", success).param("everyone", everyone).param("emails", emails)
                .param("groups", groups).param("status", success ? "SUCCEEDED" : "FAILED").param("error", errorCode)
                .query((r, _) -> new Object[] {r.getLong("observation_revision"), r.getBoolean("changed")}).optional()
                .orElseThrow(SourceException::notFound);
        // A failed read changes nobody's access: the last complete answer stays, and before it nobody reads.
        if (!success || !(Boolean) row[1]) return;
        List<DocumentId> documents = jdbc.sql("""
                SELECT DISTINCT mapping.document_id FROM documents_by_connector_credential_pair mapping
                JOIN connector_items i ON i.tenant_id = mapping.tenant_id AND i.id = mapping.connector_item_id
                WHERE mapping.tenant_id = :tenant AND mapping.connector_credential_pair_id = :source
                    AND i.provider_file_id = :file
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("file", fileId).query((r, _) -> new DocumentId(r.getObject(1, UUID.class))).list();
        events.publishEvent(new SourceAclChanged(work.tenantId(), work.sourceId(), fileId, documents, (Long) row[0]));
    }

    private static Item item(ResultSet r, int ignored) throws SQLException {
        return new Item(r.getString("provider_file_id"), "PAGE".equals(r.getString("kind")), r.getString("drive_id"),
                r.getString("path"), r.getString("list_id"), r.getString("list_item_id"), r.getString("web_url"));
    }

    /** {@code permissionsDue}: the permissions of every item the Source holds are to be read again. */
    public record Sharing(boolean sharingLinksPublic, boolean permissionsDue) {}

    /**
     * {@code path} is the address of a page, and the path below its library of a file. {@code listId},
     * {@code listItemId} and {@code webUrl} are null for an item acquired before they were remembered.
     */
    public record Item(String providerFileId, boolean page, @Nullable String driveId, @Nullable String path,
                       @Nullable String listId, @Nullable String listItemId, @Nullable String webUrl) {}
}
