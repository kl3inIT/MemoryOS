package io.memoryos.connector.persistence;

import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSourceSyncRepository {
    private final JdbcClient jdbc;
    public JdbcSourceSyncRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public SourceOperationView enqueue(TenantId tenant, SourceId source, long credentialRevision) {
        var live = jdbc.sql("""
                SELECT * FROM source_sync_attempts WHERE tenant_id = :tenant AND source_id = :source
                  AND status IN ('NOT_STARTED','IN_PROGRESS')
                """).param("tenant", tenant.value()).param("source", source.value()).query(this::operation).optional();
        if (live.isPresent()) return live.get();
        UUID id = UUID.randomUUID();
        var trace = SourceOperationTraceContext.current();
        jdbc.sql("""
                UPDATE google_drive_sources SET generation = generation + 1, error_code = NULL,
                    next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute'
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
        jdbc.sql("""
                INSERT INTO source_sync_attempts (id, tenant_id, source_id, scope_revision, credential_revision,
                    generation, origin_trace_id, origin_span_id)
                SELECT :id, s.tenant_id, s.source_id, s.revision, :credential, s.generation, :trace, :span
                FROM google_drive_sources s WHERE s.tenant_id = :tenant AND s.source_id = :source
                """).param("id", id).param("tenant", tenant.value()).param("source", source.value())
                .param("credential", credentialRevision).param("trace", trace == null ? null : trace.traceId())
                .param("span", trace == null ? null : trace.spanId()).update();
        return find(tenant, new SourceOperationId(id)).orElseThrow();
    }

    public Optional<SourceOperationView> find(TenantId tenant, SourceOperationId id) {
        return jdbc.sql("SELECT * FROM source_sync_attempts WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.value()).param("id", id.value()).query(this::operation).optional();
    }

    public List<DueSource> due(int limit) {
        return jdbc.sql("""
                SELECT s.tenant_id, s.source_id FROM google_drive_sources s
                JOIN connector_credential_pairs p ON p.tenant_id = s.tenant_id AND p.id = s.source_id
                JOIN tenants t ON t.id = s.tenant_id
                WHERE t.status = 'ACTIVE' AND p.status <> 'DELETING' AND s.next_sync_at <= CURRENT_TIMESTAMP
                  AND EXISTS (SELECT 1 FROM google_drive_roots r WHERE r.tenant_id = s.tenant_id AND r.source_id = s.source_id)
                  AND NOT EXISTS (SELECT 1 FROM source_sync_attempts a WHERE a.tenant_id = s.tenant_id AND a.source_id = s.source_id
                    AND a.status IN ('NOT_STARTED','IN_PROGRESS'))
                ORDER BY s.next_sync_at, s.source_id LIMIT :limit
                """).param("limit", Math.clamp(limit, 1, 32)).query((r, _) -> new DueSource(
                        new TenantId(r.getObject("tenant_id", UUID.class)), new SourceId(r.getObject("source_id", UUID.class)))).list();
    }

    public void postpone(TenantId tenant, SourceId source) {
        jdbc.sql("UPDATE google_drive_sources SET next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute' WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).update();
    }

    public Optional<Work> claim(TenantId tenant, SourceOperationId id, UUID delivery) {
        return WorkLeases.claim(jdbc, "source_sync_attempts", tenant.value(), id.value(), delivery, (operation, token) ->
                jdbc.sql("SELECT * FROM source_sync_attempts WHERE id = :id AND claim_token = :token")
                        .param("id", operation).param("token", token).query((r, _) -> new Work(tenant,
                                new SourceId(r.getObject("source_id", UUID.class)), id, token,
                                r.getLong("scope_revision"), r.getLong("credential_revision"), r.getLong("generation"),
                                WorkLeases.initialQueueWait(r))).single());
    }

    public boolean renew(Work work) {
        return WorkLeases.renew(jdbc, "source_sync_attempts", work.tenantId().value(), work.operationId().value(), work.claimToken());
    }

    public boolean current(Work work) {
        return jdbc.sql("""
                SELECT a.id FROM source_sync_attempts a
                JOIN google_drive_sources s ON s.tenant_id = a.tenant_id AND s.source_id = a.source_id
                JOIN connector_credential_pairs p ON p.tenant_id = a.tenant_id AND p.id = a.source_id
                JOIN tenants t ON t.id = a.tenant_id
                WHERE a.tenant_id = :tenant AND a.id = :id AND a.claim_token = :token
                  AND a.status = 'IN_PROGRESS' AND a.lease_expires_at > CURRENT_TIMESTAMP
                  AND s.revision = a.scope_revision AND s.generation = a.generation
                  AND p.status <> 'DELETING' AND t.status = 'ACTIVE'
                FOR UPDATE OF a, s
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("token", work.claimToken()).query(UUID.class).optional().isPresent();
    }

    public String phase(Work work) {
        return jdbc.sql("SELECT phase FROM source_sync_attempts WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", work.tenantId().value()).param("id", work.operationId().value()).query(String.class).single();
    }

    public void start(Work work) {
        jdbc.sql("UPDATE source_sync_attempts SET phase = 'SCAN' WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", work.tenantId().value()).param("id", work.operationId().value()).update();
        jdbc.sql("UPDATE google_drive_membership SET eligible = FALSE WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", work.tenantId().value()).param("source", work.sourceId().value()).update();
        jdbc.sql("""
                INSERT INTO google_drive_frontier (tenant_id, attempt_id, file_id, task_kind)
                SELECT tenant_id, :id, file_id, 'FILE' FROM google_drive_roots
                WHERE tenant_id = :tenant AND source_id = :source ON CONFLICT DO NOTHING
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

    public void failedNode(Work work, Node node, String code, boolean unsupported) {
        jdbc.sql("""
                UPDATE google_drive_frontier SET attempts = attempts + 1, error_code = :code,
                    state = CASE WHEN :unsupported THEN 'UNSUPPORTED' WHEN attempts >= 2 THEN 'FAILED' ELSE 'PENDING' END
                WHERE tenant_id = :tenant AND attempt_id = :id AND file_id = :file AND task_kind = :kind
                """).param("code", WorkLeases.safeErrorCode(code)).param("unsupported", unsupported)
                .param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("file", node.fileId()).param("kind", node.kind()).update();
    }

    public boolean hasFailures(Work work) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM google_drive_frontier WHERE tenant_id = :tenant AND attempt_id = :id AND state IN ('FAILED','UNSUPPORTED'))")
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

    public void finish(Work work) {
        jdbc.sql("""
                UPDATE google_drive_membership SET eligible = NOT excluded AND root_id IS NOT NULL
                  AND generation = :generation
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("generation", work.generation()).update();
        jdbc.sql("""
                UPDATE google_drive_sources SET last_synced_at = CURRENT_TIMESTAMP,
                  next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute', error_code = NULL
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value()).update();
        terminal(work, "SUCCEEDED", null);
    }

    public void terminal(Work work, String status, @Nullable String code) {
        int updated = jdbc.sql("""
                UPDATE source_sync_attempts SET status = :status, error_code = :code, completed_at = CURRENT_TIMESTAMP,
                    claim_token = NULL, lease_expires_at = NULL WHERE tenant_id = :tenant AND id = :id AND claim_token = :token
                """).param("status", status).param("code", code).param("tenant", work.tenantId().value())
                .param("id", work.operationId().value()).param("token", work.claimToken()).update();
        if (updated == 1 && code != null) jdbc.sql("""
                UPDATE google_drive_sources SET error_code = :code,
                    next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute' WHERE tenant_id = :tenant AND source_id = :source
                    AND revision = :scope AND generation = :generation
                """).param("code", code).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("scope", work.scopeRevision()).param("generation", work.generation()).update();
    }

    public void continuation(Work work, @Nullable String errorCode) {
        jdbc.sql("""
                UPDATE source_sync_attempts SET status = 'NOT_STARTED', claim_token = NULL, lease_expires_at = NULL,
                  failure_attempts = 0,
                  delivery_id = NULL, dispatch_token = NULL, dispatch_lease_expires_at = NULL, redis_message_id = NULL,
                  dispatched_at = NULL, next_dispatch_at = CURRENT_TIMESTAMP + INTERVAL '1 second', error_code = :error
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token
                """).param("error", errorCode).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("token", work.claimToken()).update();
    }

    public void retry(Work work, String error) {
        var status = jdbc.sql("""
                UPDATE source_sync_attempts SET failure_attempts = failure_attempts + 1,
                  status = CASE WHEN failure_attempts >= 5 THEN 'FAILED' ELSE 'NOT_STARTED' END,
                  completed_at = CASE WHEN failure_attempts >= 5 THEN CURRENT_TIMESTAMP ELSE NULL END,
                  claim_token = NULL, lease_expires_at = NULL, delivery_id = NULL, dispatch_token = NULL,
                  dispatch_lease_expires_at = NULL, redis_message_id = NULL, dispatched_at = NULL,
                  next_dispatch_at = CURRENT_TIMESTAMP + INTERVAL '30 seconds', error_code = :error
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token AND lease_expires_at > CURRENT_TIMESTAMP
                RETURNING status
                """).param("error", WorkLeases.safeErrorCode(error)).param("tenant", work.tenantId().value())
                .param("id", work.operationId().value()).param("token", work.claimToken()).query(String.class).optional();
        if (status.filter("FAILED"::equals).isPresent()) jdbc.sql("""
                UPDATE google_drive_sources SET error_code = :error, next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute'
                WHERE tenant_id = :tenant AND source_id = :source AND revision = :scope AND generation = :generation
                """).param("error", error).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("scope", work.scopeRevision()).param("generation", work.generation()).update();
    }

    public void cancel(TenantId tenant, SourceId source) {
        jdbc.sql("""
                UPDATE source_sync_attempts SET status = 'SUPERSEDED', claim_token = NULL, lease_expires_at = NULL,
                    completed_at = CURRENT_TIMESTAMP WHERE tenant_id = :tenant AND source_id = :source
                    AND status IN ('NOT_STARTED','IN_PROGRESS')
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    public void exclude(TenantId tenant, SourceId source, SourceItemId item) {
        jdbc.sql("""
                UPDATE google_drive_membership m SET excluded = TRUE, eligible = FALSE
                FROM connector_items i WHERE m.tenant_id = :tenant AND m.source_id = :source
                  AND i.tenant_id = m.tenant_id AND i.id = :item AND m.file_id = i.provider_file_id
                """).param("tenant", tenant.value()).param("source", source.value()).param("item", item.value()).update();
    }

    private SourceOperationView operation(ResultSet r, int ignored) throws SQLException {
        return new SourceOperationView(new SourceOperationId(r.getObject("id", UUID.class)), SourceOperationType.SYNC_SOURCE,
                JdbcSourceRepository.operationStatus(r.getString("status")), r.getTimestamp("created_at").toInstant(),
                JdbcSourceRepository.instant(r, "completed_at"), r.getString("error_code"));
    }

    public record Node(String fileId, String kind, @Nullable String pageToken, int attempts) {}
    public record DueSource(TenantId tenantId, SourceId sourceId) {}
}
