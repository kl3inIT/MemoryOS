package io.memoryos.connector.lark.persistence;

import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.LarkGateway.Collaborator;
import io.memoryos.connector.LarkGateway.Permissions;
import io.memoryos.connector.SourceAclChanged;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.sync.persistence.WorkLeases;
import io.memoryos.document.DocumentId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * What Lark shares each object of a SYNC Source with, as its last complete read. The grants a reader is checked
 * against are derived from these rows by {@code JdbcSourceDocumentRepository}.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcLarkAclRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public JdbcLarkAclRepository(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Whether the Source admits readers by Lark's own sharing, which is when that sharing is read. */
    public boolean syncsPermissions(Work work) {
        return jdbc.sql("""
                SELECT access_type = 'SYNC' FROM connector_credential_pairs WHERE tenant_id = :tenant AND id = :source
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .query(Boolean.class).optional().orElse(false);
    }

    /** Caller holds the Source's fences in its transaction. */
    public void recordSuccess(Work work, String fileId, Permissions permissions) {
        record(work, fileId, members(permissions.collaborators()), permissions.linkShare(), null);
    }

    /** Keeps the last complete answer in force, independently of this failed attempt. */
    public void recordFailure(Work work, String fileId, String errorCode) {
        record(work, fileId, null, null, WorkLeases.safeErrorCode(errorCode));
    }

    private void record(Work work, String fileId, @Nullable String members, @Nullable String linkShare,
            @Nullable String errorCode) {
        boolean success = members != null;
        var row = jdbc.sql("""
                WITH prior AS (
                    SELECT members_json, link_share, status FROM lark_acl_snapshots
                    WHERE tenant_id = :tenant AND source_id = :source AND file_id = :file
                ), upserted AS (
                    INSERT INTO lark_acl_snapshots (tenant_id, source_id, file_id, observation_revision, members_json,
                        link_share, status, last_attempt_at, last_success_at, error_code)
                    VALUES (:tenant, :source, :file, CASE WHEN :success THEN 1 ELSE 0 END, CAST(:members AS jsonb),
                        :link, :status, statement_timestamp(), CASE WHEN :success THEN statement_timestamp() END, :error)
                    ON CONFLICT (tenant_id, source_id, file_id) DO UPDATE SET
                        observation_revision = lark_acl_snapshots.observation_revision + EXCLUDED.observation_revision,
                        members_json = COALESCE(EXCLUDED.members_json, lark_acl_snapshots.members_json),
                        link_share = CASE WHEN :success THEN EXCLUDED.link_share ELSE lark_acl_snapshots.link_share END,
                        status = EXCLUDED.status, last_attempt_at = EXCLUDED.last_attempt_at,
                        last_success_at = COALESCE(EXCLUDED.last_success_at, lark_acl_snapshots.last_success_at),
                        error_code = EXCLUDED.error_code
                    RETURNING observation_revision, members_json, link_share
                )
                SELECT u.observation_revision,
                    u.members_json IS DISTINCT FROM (SELECT prior.members_json FROM prior)
                        OR u.link_share IS DISTINCT FROM (SELECT prior.link_share FROM prior) AS changed
                FROM upserted u
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("file", fileId).param("success", success).param("members", members).param("link", linkShare)
                .param("status", success ? "SUCCEEDED" : "FAILED").param("error", errorCode)
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

    public void forget(Work work, String fileId) {
        jdbc.sql("DELETE FROM lark_acl_snapshots WHERE tenant_id = :tenant AND source_id = :source AND file_id = :file")
                .param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("file", fileId).update();
    }

    /** Lark's member type and ID of each collaborator, once each and in a stable order. */
    private static String members(List<Collaborator> collaborators) {
        var entries = collaborators.stream()
                .map(member -> Map.entry(member.memberType(), member.memberId())).distinct()
                .sorted(Map.Entry.<String, String>comparingByKey().thenComparing(Map.Entry.comparingByValue(
                        Comparator.naturalOrder())))
                .map(entry -> {
                    var value = new LinkedHashMap<String, String>();
                    value.put("type", entry.getKey());
                    value.put("id", entry.getValue());
                    return value;
                }).toList();
        return JSON.writeValueAsString(entries);
    }
}
