package io.memoryos.connector.googledrive.persistence;

import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.DueSource;
import io.memoryos.connector.sync.persistence.WorkLeases;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The Google Drive walk of a synchronization run: its frontier of files and folders still to read, and the
 * membership of every file in the Source's roots. The attempt itself is {@link JdbcSourceSyncRepository}'s.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcGoogleDriveSyncRepository {
    private final JdbcClient jdbc;
    private final JdbcSourceSyncRepository attempts;

    public JdbcGoogleDriveSyncRepository(JdbcClient jdbc, JdbcSourceSyncRepository attempts) {
        this.jdbc = jdbc;
        this.attempts = attempts;
    }

    public List<DueSource> due(int limit) {
        return jdbc.sql("""
                SELECT s.tenant_id, s.source_id FROM google_drive_sources s
                JOIN connector_credential_pairs p ON p.tenant_id = s.tenant_id AND p.id = s.source_id
                JOIN tenants t ON t.id = s.tenant_id
                WHERE t.status = 'ACTIVE' AND p.status NOT IN ('DELETING', 'PAUSED') AND s.next_sync_at <= CURRENT_TIMESTAMP
                  AND NOT s.sync_paused
                  AND EXISTS (SELECT 1 FROM google_drive_roots r WHERE r.tenant_id = s.tenant_id AND r.source_id = s.source_id)
                  AND NOT EXISTS (SELECT 1 FROM source_sync_attempts a WHERE a.tenant_id = s.tenant_id AND a.source_id = s.source_id
                    AND a.status IN ('NOT_STARTED','IN_PROGRESS'))
                ORDER BY s.next_sync_at, s.source_id LIMIT :limit
                """).param("limit", Math.clamp(limit, 1, 32)).query((r, _) -> new DueSource(
                        new TenantId(r.getObject("tenant_id", UUID.class)), new SourceId(r.getObject("source_id", UUID.class)))).list();
    }

    public String phase(Work work) {
        return jdbc.sql("SELECT phase FROM source_sync_attempts WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", work.tenantId().value()).param("id", work.operationId().value()).query(String.class).single();
    }

    /** Seeds the frontier with the roots and approved links; until a file is confirmed it is not eligible. */
    public void start(Work work) {
        jdbc.sql("UPDATE source_sync_attempts SET phase = 'SCAN' WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", work.tenantId().value()).param("id", work.operationId().value()).update();
        jdbc.sql("UPDATE google_drive_membership SET eligible = FALSE WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", work.tenantId().value()).param("source", work.sourceId().value()).update();
        jdbc.sql("""
                INSERT INTO google_drive_frontier (tenant_id, attempt_id, file_id, task_kind)
                SELECT tenant_id, :id, file_id, 'FILE' FROM google_drive_roots
                WHERE tenant_id = :tenant AND source_id = :source
                UNION
                SELECT tenant_id, :id, file_id, 'FILE' FROM google_drive_link_approvals
                WHERE tenant_id = :tenant AND source_id = :source
                ON CONFLICT DO NOTHING
                """).param("id", work.operationId().value()).param("tenant", work.tenantId().value())
                .param("source", work.sourceId().value()).update();
    }

    public Optional<Node> next(Work work) {
        return jdbc.sql("""
                SELECT file_id, task_kind, page_token, attempts FROM google_drive_frontier
                WHERE tenant_id = :tenant AND attempt_id = :id AND state = 'PENDING'
                ORDER BY attempts, created_at, file_id, task_kind LIMIT 1
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .query((r, _) -> new Node(r.getString("file_id"), r.getString("task_kind"),
                        r.getString("page_token"), r.getInt("attempts"))).optional();
    }

    public void enqueueNode(Work work, String file, String kind) {
        jdbc.sql("""
                INSERT INTO google_drive_frontier (tenant_id, attempt_id, file_id, task_kind)
                VALUES (:tenant, :id, :file, :kind)
                ON CONFLICT (tenant_id, attempt_id, file_id, task_kind) DO NOTHING
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("file", file).param("kind", kind).update();
    }

    public void checkpoint(Work work, Node node, @Nullable String next) {
        jdbc.sql("""
                UPDATE google_drive_frontier SET page_token = :page, state = :state, error_code = NULL, attempts = 0
                WHERE tenant_id = :tenant AND attempt_id = :id AND file_id = :file AND task_kind = :kind
                """).param("page", next).param("state", next == null ? "DONE" : "PENDING")
                .param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("file", node.fileId()).param("kind", node.kind()).update();
    }

    /**
     * Counts a failed read of a node, which the run retries twice before giving the node up. Returns the node's
     * state: {@code PENDING} while it is retried, then {@code FAILED}, or {@code UNSUPPORTED} at once.
     */
    public String failNode(Work work, Node node, String code, boolean unsupported) {
        return jdbc.sql("""
                UPDATE google_drive_frontier SET attempts = attempts + 1, error_code = :code,
                    state = CASE WHEN :unsupported THEN 'UNSUPPORTED' WHEN attempts >= 2 THEN 'FAILED' ELSE 'PENDING' END
                WHERE tenant_id = :tenant AND attempt_id = :id AND file_id = :file AND task_kind = :kind
                RETURNING state
                """).param("code", WorkLeases.safeErrorCode(code)).param("unsupported", unsupported)
                .param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("file", node.fileId()).param("kind", node.kind()).query(String.class).optional().orElse("PENDING");
    }

    /**
     * A node this run gave up on, or could not read in a supported form, leaves the listing incomplete: what lies
     * beneath it is unknown, so nothing may be pruned.
     */
    public boolean hasFailedNodes(Work work) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM google_drive_frontier WHERE tenant_id = :tenant AND attempt_id = :id AND state IN ('FAILED', 'UNSUPPORTED'))")
                .param("tenant", work.tenantId().value()).param("id", work.operationId().value()).query(Boolean.class).single();
    }

    public boolean excluded(Work work, String file) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM google_drive_membership
                WHERE tenant_id = :tenant AND source_id = :source AND file_id = :file AND excluded)
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("file", file).query(Boolean.class).single();
    }

    public void observe(Work work, String file, @Nullable String root, @Nullable String version) {
        jdbc.sql("""
                INSERT INTO google_drive_membership (tenant_id, source_id, file_id, root_id, generation, provider_version)
                VALUES (:tenant, :source, :file, :root, :generation, :version)
                ON CONFLICT (tenant_id, source_id, file_id) DO UPDATE SET root_id = EXCLUDED.root_id,
                  generation = EXCLUDED.generation, provider_version = EXCLUDED.provider_version, error_code = NULL,
                  eligible = CASE WHEN EXCLUDED.root_id IS NULL THEN FALSE ELSE google_drive_membership.eligible END
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value()).param("file", file)
                .param("root", root).param("generation", work.generation()).param("version", version).update();
    }

    /**
     * The roots of folders this run already placed, so an ancestor walk stops at the first folder it listed
     * instead of asking Drive again. A folder this run found outside every root maps to null.
     */
    public Map<String, Optional<String>> placed(Work work, Collection<String> folders) {
        var placed = new HashMap<String, Optional<String>>();
        if (folders.isEmpty()) return placed;
        jdbc.sql("""
                SELECT file_id, root_id FROM google_drive_membership
                WHERE tenant_id = :tenant AND source_id = :source AND generation = :generation
                  AND file_id IN (:files)
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("generation", work.generation()).param("files", List.copyOf(folders))
                .query((RowCallbackHandler) r ->
                        placed.put(r.getString("file_id"), Optional.ofNullable(r.getString("root_id"))));
        return placed;
    }

    public List<SourceItemId> pruneCandidates(Work work) {
        return jdbc.sql("""
                SELECT i.id FROM connector_items i
                JOIN connector_credential_pairs p ON p.tenant_id = i.tenant_id AND p.connector_id = i.connector_id
                LEFT JOIN google_drive_membership m ON m.tenant_id = p.tenant_id AND m.source_id = p.id AND m.file_id = i.provider_file_id
                WHERE p.tenant_id = :tenant AND p.id = :source AND i.status <> 'DELETING'
                  AND (m.excluded OR m.root_id IS NULL OR m.generation <> :generation)
                ORDER BY i.id LIMIT 32
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("generation", work.generation())
                .query((r, _) -> new SourceItemId(r.getObject("id", UUID.class))).list();
    }

    /** After an incomplete listing, only files this run confirmed become eligible again. */
    public void releaseConfirmed(Work work) {
        jdbc.sql("""
                UPDATE google_drive_membership m SET eligible = TRUE
                WHERE m.tenant_id = :tenant AND m.source_id = :source AND m.generation = :generation
                  AND NOT m.eligible AND NOT m.excluded AND m.root_id IS NOT NULL
                  AND EXISTS (
                    SELECT 1 FROM google_drive_frontier f
                    WHERE f.tenant_id = m.tenant_id AND f.attempt_id = :attempt
                      AND f.file_id = m.file_id AND f.task_kind = 'FILE' AND f.state = 'DONE')
                  AND EXISTS (
                    SELECT 1 FROM connector_credential_pairs p
                    JOIN connector_items i ON i.tenant_id = p.tenant_id AND i.connector_id = p.connector_id
                    JOIN connector_item_versions v ON v.tenant_id = i.tenant_id AND v.id = i.current_version_id
                    WHERE p.tenant_id = m.tenant_id AND p.id = m.source_id AND i.provider_file_id = m.file_id
                      AND i.status <> 'DELETING' AND v.provider_version = m.provider_version
                      AND v.scope_revision = :scope AND v.credential_revision = :credential)
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("generation", work.generation()).param("attempt", work.operationId().value())
                .param("scope", work.scopeRevision()).param("credential", work.credentialRevision()).update();
    }

    /** After a complete listing, every file the run placed in a root is eligible and nothing else is. */
    public void releaseListed(Work work) {
        jdbc.sql("""
                UPDATE google_drive_membership SET eligible = NOT excluded AND root_id IS NOT NULL
                  AND generation = :generation
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("generation", work.generation()).update();
    }

    /**
     * Enqueues a resumed run after a pause. When the latest run was canceled by pause and its scope/credential
     * revisions still match, the new attempt inherits its phase, counters and the full frontier so traversal
     * continues from the retained checkpoint. Otherwise a fresh run starts. Returns the live run when one exists.
     */
    public Optional<SourceOperationView> enqueueResumed(TenantId tenant, SourceId source, long credentialRevision,
            @Nullable ActorId actor) {
        var live = attempts.live(tenant, source);
        if (live.isPresent()) return live;
        UUID id = UUID.randomUUID();
        var trace = SourceOperationTraceContext.current();
        jdbc.sql("""
                UPDATE google_drive_sources SET generation = generation + 1,
                    next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute'
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
        attempts.clearSyncError(tenant, source);
        int created = jdbc.sql("""
                INSERT INTO source_sync_attempts (id, tenant_id, source_id, scope_revision, credential_revision,
                    generation, phase, history_version, trigger_kind, actor_id,
                """ + JdbcSourceSyncRepository.COUNTERS + """
                )
                SELECT :id, s.tenant_id, s.source_id, s.revision, :credential, s.generation,
                    donor.phase, 1, 'RESUMED', :actor,
                    donor.scanned, donor.acquired, donor.unchanged, donor.already_pending,
                    donor.acquisition_failed, donor.skipped, donor.removed,
                    0, 0, 0, 0, 0
                FROM google_drive_sources s
                JOIN LATERAL (
                    SELECT * FROM source_sync_attempts a
                    WHERE a.tenant_id = s.tenant_id AND a.source_id = s.source_id
                      AND a.status = 'CANCELLED' AND a.error_code = 'SOURCE_PAUSED'
                      AND a.scope_revision = s.revision AND a.credential_revision = :credential
                    ORDER BY a.created_at DESC LIMIT 1
                ) donor ON TRUE
                WHERE s.tenant_id = :tenant AND s.source_id = :source
                """).param("id", id).param("tenant", tenant.value()).param("source", source.value())
                .param("credential", credentialRevision)
                .param("actor", actor == null ? null : actor.value()).update();
        if (created == 0) {
            jdbc.sql("""
                    INSERT INTO source_sync_attempts (id, tenant_id, source_id, scope_revision, credential_revision,
                        generation, origin_trace_id, origin_span_id, history_version, trigger_kind, actor_id,
                    """ + JdbcSourceSyncRepository.COUNTERS + """
                    )
                    SELECT :id, s.tenant_id, s.source_id, s.revision, :credential, s.generation, :trace, :span,
                        1, 'RESUMED', :actor, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
                    FROM google_drive_sources s WHERE s.tenant_id = :tenant AND s.source_id = :source
                    """).param("id", id).param("tenant", tenant.value()).param("source", source.value())
                    .param("credential", credentialRevision).param("trace", trace == null ? null : trace.traceId())
                    .param("span", trace == null ? null : trace.spanId())
                    .param("actor", actor == null ? null : actor.value()).update();
            return attempts.find(tenant, new SourceOperationId(id));
        }
        jdbc.sql("""
                INSERT INTO google_drive_frontier (tenant_id, attempt_id, file_id, task_kind, page_token, state, attempts, error_code)
                SELECT :tenant, :id, file_id, task_kind, page_token, state, attempts, error_code
                FROM google_drive_frontier
                WHERE tenant_id = :tenant AND attempt_id = (
                    SELECT a.id FROM source_sync_attempts a
                    WHERE a.tenant_id = :tenant AND a.source_id = :source
                      AND a.status = 'CANCELLED' AND a.error_code = 'SOURCE_PAUSED'
                    ORDER BY a.created_at DESC LIMIT 1
                )
                """).param("id", id).param("tenant", tenant.value()).param("source", source.value()).update();
        return attempts.find(tenant, new SourceOperationId(id));
    }

    public void exclude(TenantId tenant, SourceId source, SourceItemId item) {
        jdbc.sql("""
                UPDATE google_drive_membership m SET excluded = TRUE, eligible = FALSE
                FROM connector_items i WHERE m.tenant_id = :tenant AND m.source_id = :source
                  AND i.tenant_id = m.tenant_id AND i.id = :item AND m.file_id = i.provider_file_id
                """).param("tenant", tenant.value()).param("source", source.value()).param("item", item.value()).update();
    }

    public record Node(String fileId, String kind, @Nullable String pageToken, int attempts) {}
}
