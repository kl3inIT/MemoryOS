package io.memoryos.connector.persistence;

import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.SourceRunTrigger;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Runs of a SharePoint Source. A refresh reads the change log since the last successful window; a prune
 * lists the whole scope and removes what it no longer finds, but only when the listing completed.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSharePointSyncRepository {
    /** Repeats part of the previous window so a change written during it is not missed. */
    public static final java.time.Duration OVERLAP = java.time.Duration.ofMinutes(30);

    private final JdbcClient jdbc;
    private final JdbcSourceSyncRepository attempts;

    public JdbcSharePointSyncRepository(JdbcClient jdbc, JdbcSourceSyncRepository attempts) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
    }

    public SourceOperationView enqueue(TenantId tenant, SourceId source, long credentialRevision,
            SourceRunTrigger trigger, @Nullable ActorId actor) {
        var live = jdbc.sql("""
                SELECT * FROM source_sync_attempts WHERE tenant_id = :tenant AND source_id = :source
                  AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new SourceOperationId(r.getObject("id", UUID.class))).optional();
        if (live.isPresent()) return attempts.find(tenant, live.get()).orElseThrow();
        UUID id = UUID.randomUUID();
        var trace = SourceOperationTraceContext.current();
        jdbc.sql("""
                UPDATE sharepoint_sources SET generation = generation + 1, error_code = NULL,
                    next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute'
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
        jdbc.sql("""
                INSERT INTO source_sync_attempts (id, tenant_id, source_id, scope_revision, credential_revision,
                    generation, origin_trace_id, origin_span_id, history_version, trigger_kind, actor_id,
                    scanned, acquired, unchanged, already_pending, acquisition_failed, skipped, removed,
                    published, indexing_pending, indexing_failed, indexing_superseded, indexing_cancelled)
                SELECT :id, s.tenant_id, s.source_id, s.scope_revision, :credential, s.generation, :trace, :span,
                    1, :trigger, :actor, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
                FROM sharepoint_sources s WHERE s.tenant_id = :tenant AND s.source_id = :source
                """).param("id", id).param("tenant", tenant.value()).param("source", source.value())
                .param("credential", credentialRevision).param("trace", trace == null ? null : trace.traceId())
                .param("span", trace == null ? null : trace.spanId()).param("trigger", trigger.name())
                .param("actor", actor == null ? null : actor.value()).update();
        return attempts.find(tenant, new SourceOperationId(id)).orElseThrow();
    }

    /** Sources whose refresh or prune is due, newest schedule first, skipping those already running. */
    public List<DueSource> due(int limit) {
        return jdbc.sql("""
                SELECT s.tenant_id, s.source_id,
                    (s.prune_interval_hours > 0 AND s.next_prune_at <= CURRENT_TIMESTAMP) AS prune
                FROM sharepoint_sources s
                JOIN connector_credential_pairs p ON p.tenant_id = s.tenant_id AND p.id = s.source_id
                JOIN tenants t ON t.id = s.tenant_id
                WHERE t.status = 'ACTIVE' AND p.status NOT IN ('DELETING', 'PAUSED') AND NOT s.sync_paused
                  AND (s.next_sync_at <= CURRENT_TIMESTAMP
                       OR (s.prune_interval_hours > 0 AND s.next_prune_at <= CURRENT_TIMESTAMP))
                  AND (s.scope_mode = 'ALL_SITES'
                       OR EXISTS (SELECT 1 FROM sharepoint_roots r
                           WHERE r.tenant_id = s.tenant_id AND r.source_id = s.source_id))
                  AND NOT EXISTS (SELECT 1 FROM source_sync_attempts a
                      WHERE a.tenant_id = s.tenant_id AND a.source_id = s.source_id
                        AND a.status IN ('NOT_STARTED', 'IN_PROGRESS'))
                ORDER BY LEAST(s.next_sync_at, s.next_prune_at), s.source_id LIMIT :limit
                """).param("limit", Math.clamp(limit, 1, 32))
                .query((r, _) -> new DueSource(new TenantId(r.getObject("tenant_id", UUID.class)),
                        new SourceId(r.getObject("source_id", UUID.class)), r.getBoolean("prune"))).list();
    }

    /**
     * Moves the next refresh forward when a scheduled run is queued. The prune schedule is left alone: the run
     * decides from it whether it is a prune, and only a finished prune moves it.
     */
    public void scheduleNextSync(TenantId tenant, SourceId source) {
        jdbc.sql("""
                UPDATE sharepoint_sources
                SET next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute'
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    /** Backs off a Source whose run could not be queued or failed, including a prune that is already due. */
    public void postpone(TenantId tenant, SourceId source) {
        jdbc.sql("""
                UPDATE sharepoint_sources
                SET next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute',
                    -- A due prune waits for the next refresh slot instead of being retried on every tick.
                    next_prune_at = CASE WHEN prune_interval_hours > 0 AND next_prune_at <= CURRENT_TIMESTAMP
                        THEN CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute' ELSE next_prune_at END
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    public boolean automaticSyncEnabled(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT NOT sync_paused FROM sharepoint_sources WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value())
                .query(Boolean.class).optional().orElse(false);
    }

    /** The attempt must still match the Source it was created for, or the run stops instead of writing. */
    public boolean current(Work work) {
        return jdbc.sql("""
                SELECT a.id FROM source_sync_attempts a
                JOIN sharepoint_sources s ON s.tenant_id = a.tenant_id AND s.source_id = a.source_id
                JOIN connector_credential_pairs p ON p.tenant_id = a.tenant_id AND p.id = a.source_id
                JOIN tenants t ON t.id = a.tenant_id
                WHERE a.tenant_id = :tenant AND a.id = :id AND a.claim_token = :token
                  AND a.status = 'IN_PROGRESS' AND a.lease_expires_at > CURRENT_TIMESTAMP
                  AND s.scope_revision = a.scope_revision AND s.generation = a.generation
                  AND p.status NOT IN ('DELETING', 'PAUSED') AND t.status = 'ACTIVE'
                FOR UPDATE OF a, s
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("token", work.claimToken()).query(UUID.class).optional().isPresent();
    }

    public SourceState state(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT scope_mode, include_documents, include_pages, prune_interval_hours, refresh_window_end,
                       tenant_host, (prune_interval_hours > 0 AND next_prune_at <= CURRENT_TIMESTAMP) AS prune_due
                FROM sharepoint_sources WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new SourceState(r.getString("scope_mode"), r.getBoolean("include_documents"),
                        r.getBoolean("include_pages"), r.getInt("prune_interval_hours"),
                        r.getTimestamp("refresh_window_end") == null ? null : r.getTimestamp("refresh_window_end").toInstant(),
                        r.getString("tenant_host"), r.getBoolean("prune_due")))
                .optional().orElseThrow(io.memoryos.connector.SourceException::notFound);
    }

    /** Opens the run for this attempt, or returns the one a previous delivery left unfinished. */
    public Run openRun(Work work, String kind, @Nullable Instant windowStart, Instant windowEnd) {
        var existing = jdbc.sql("""
                SELECT * FROM sharepoint_sync_runs
                WHERE tenant_id = :tenant AND source_sync_attempt_id = :attempt AND status = 'IN_PROGRESS'
                """).param("tenant", work.tenantId().value()).param("attempt", work.operationId().value())
                .query(this::run).optional();
        if (existing.isPresent()) return existing.get();
        // The caller holds the current attempt, so a run another attempt left open (cancelled, superseded or
        // abandoned by a worker) can no longer finish and must not block this one.
        jdbc.sql("""
                UPDATE sharepoint_sync_runs SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND source_id = :source AND status = 'IN_PROGRESS'
                  AND source_sync_attempt_id IS DISTINCT FROM :attempt
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("attempt", work.operationId().value()).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO sharepoint_sync_runs (id, tenant_id, source_id, source_sync_attempt_id, kind,
                    window_start, window_end)
                VALUES (:id, :tenant, :source, :attempt, :kind, :start, :end)
                """).param("id", id).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("attempt", work.operationId().value()).param("kind", kind)
                .param("start", windowStart == null ? null : java.sql.Timestamp.from(windowStart))
                .param("end", java.sql.Timestamp.from(windowEnd)).update();
        return jdbc.sql("SELECT * FROM sharepoint_sync_runs WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", work.tenantId().value()).param("id", id).query(this::run).single();
    }

    public void checkpoint(Run run, @Nullable String driveId, @Nullable String siteId, @Nullable String link) {
        checkpoint(run, driveId, siteId, link, null, List.of());
    }

    /** Also records where a folder walk stands: the folder being listed and the folders still waiting. */
    public void checkpoint(Run run, @Nullable String driveId, @Nullable String siteId, @Nullable String link,
            @Nullable String folderId, List<String> pendingFolders) {
        jdbc.sql("""
                UPDATE sharepoint_sync_runs SET checkpoint_drive_id = :drive, checkpoint_site_id = :site,
                    checkpoint_link = :link, checkpoint_folder_id = :folder, checkpoint_folders = :folders
                WHERE tenant_id = :tenant AND id = :id AND status = 'IN_PROGRESS'
                """).param("drive", driveId).param("site", siteId).param("link", link).param("folder", folderId)
                .param("folders", pendingFolders.isEmpty() ? null : String.join("\n", pendingFolders))
                .param("tenant", run.tenantId().value()).param("id", run.id()).update();
    }

    /** Closes the run of an attempt that ended without completing it. */
    private void closeRun(Work work, String status, @Nullable String code) {
        jdbc.sql("""
                UPDATE sharepoint_sync_runs SET status = :status, error_code = :code,
                    completed_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND source_sync_attempt_id = :attempt AND status = 'IN_PROGRESS'
                """).param("status", status).param("code", code == null ? null : WorkLeases.safeErrorCode(code))
                .param("tenant", work.tenantId().value()).param("attempt", work.operationId().value()).update();
    }

    /** Records that the run listed its whole scope, which is what allows a prune to remove anything. */
    public void listingComplete(Run run) {
        jdbc.sql("""
                UPDATE sharepoint_sync_runs SET listing_complete = TRUE
                WHERE tenant_id = :tenant AND id = :id AND status = 'IN_PROGRESS'
                """).param("tenant", run.tenantId().value()).param("id", run.id()).update();
    }

    public void completeRun(Run run, String status, @Nullable String code) {
        jdbc.sql("""
                UPDATE sharepoint_sync_runs SET status = :status, error_code = :code,
                    completed_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND id = :id AND status = 'IN_PROGRESS'
                """).param("status", status).param("code", code).param("tenant", run.tenantId().value())
                .param("id", run.id()).update();
    }

    /** Remembers an item the Source holds, and marks it seen when a prune run is listing the scope. */
    public void observe(TenantId tenant, SourceId source, String providerFileId, String kind, @Nullable String driveId,
            @Nullable String siteId, @Nullable String name, @Nullable String path, @Nullable String contentVersion,
            @Nullable String eTag, long size, @Nullable UUID pruneRun) {
        jdbc.sql("""
                INSERT INTO sharepoint_items (tenant_id, source_id, provider_file_id, kind, drive_id, site_id, name,
                    path, content_version, e_tag, size_bytes, last_seen_at, last_seen_prune_run)
                VALUES (:tenant, :source, :file, :kind, :drive, :site, :name, :path, :version, :etag, :size,
                    CURRENT_TIMESTAMP, :run)
                ON CONFLICT (tenant_id, source_id, provider_file_id) DO UPDATE SET kind = EXCLUDED.kind,
                    drive_id = EXCLUDED.drive_id, site_id = EXCLUDED.site_id, name = EXCLUDED.name,
                    path = EXCLUDED.path, content_version = EXCLUDED.content_version, e_tag = EXCLUDED.e_tag,
                    size_bytes = EXCLUDED.size_bytes, last_seen_at = CURRENT_TIMESTAMP,
                    last_seen_prune_run = COALESCE(EXCLUDED.last_seen_prune_run, sharepoint_items.last_seen_prune_run)
                """).param("tenant", tenant.value()).param("source", source.value()).param("file", providerFileId)
                .param("kind", kind).param("drive", driveId).param("site", siteId).param("name", name)
                .param("path", path).param("version", contentVersion).param("etag", eTag).param("size", size)
                .param("run", pruneRun).update();
    }

    public void forget(TenantId tenant, SourceId source, String providerFileId) {
        jdbc.sql("""
                DELETE FROM sharepoint_items
                WHERE tenant_id = :tenant AND source_id = :source AND provider_file_id = :file
                """).param("tenant", tenant.value()).param("source", source.value()).param("file", providerFileId).update();
    }

    /** The stored item for a provider identifier, which is all a tombstone carries. */
    public Optional<SourceItemId> item(TenantId tenant, SourceId source, String providerFileId) {
        return jdbc.sql("""
                SELECT i.id FROM connector_items i
                JOIN connector_credential_pairs p ON p.tenant_id = i.tenant_id AND p.connector_id = i.connector_id
                WHERE p.tenant_id = :tenant AND p.id = :source AND i.provider_file_id = :file
                  AND i.status <> 'DELETING'
                """).param("tenant", tenant.value()).param("source", source.value()).param("file", providerFileId)
                .query((r, _) -> new SourceItemId(r.getObject("id", UUID.class))).optional();
    }

    /** Items the finished prune listing did not see, in bounded batches. */
    public List<Missing> missing(TenantId tenant, SourceId source, UUID pruneRun) {
        return jdbc.sql("""
                SELECT i.id, i.provider_file_id FROM connector_items i
                JOIN connector_credential_pairs p ON p.tenant_id = i.tenant_id AND p.connector_id = i.connector_id
                LEFT JOIN sharepoint_items s ON s.tenant_id = p.tenant_id AND s.source_id = p.id
                    AND s.provider_file_id = i.provider_file_id
                WHERE p.tenant_id = :tenant AND p.id = :source AND i.status <> 'DELETING'
                  AND (s.provider_file_id IS NULL OR s.last_seen_prune_run IS DISTINCT FROM :run)
                ORDER BY i.id LIMIT 32
                """).param("tenant", tenant.value()).param("source", source.value()).param("run", pruneRun)
                .query((r, _) -> new Missing(new SourceItemId(r.getObject("id", UUID.class)),
                        r.getString("provider_file_id"))).list();
    }

    public void counted(Work work, String column, int count) {
        if (count == 0) return;
        String allowed = switch (column) {
            case "scanned", "acquired", "unchanged", "skipped", "removed", "acquisition_failed" -> column;
            default -> throw new IllegalArgumentException("unknown SharePoint run counter " + column);
        };
        jdbc.sql("UPDATE source_sync_attempts SET " + allowed + " = " + allowed
                + " + :count WHERE tenant_id = :tenant AND id = :id")
                .param("count", count).param("tenant", work.tenantId().value())
                .param("id", work.operationId().value()).update();
    }

    /** Ends a successful refresh, recording the window its next run continues from. */
    public void finishRefresh(Work work, Instant windowEnd) {
        jdbc.sql("""
                UPDATE sharepoint_sources SET refresh_window_end = :end, last_synced_at = CURRENT_TIMESTAMP,
                    next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute', error_code = NULL
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("end", java.sql.Timestamp.from(windowEnd)).param("tenant", work.tenantId().value())
                .param("source", work.sourceId().value()).update();
        attempts.terminal(work, "SUCCEEDED", null, null, null);
    }

    public void finishPrune(Work work) {
        jdbc.sql("""
                UPDATE sharepoint_sources SET last_pruned_at = CURRENT_TIMESTAMP,
                    next_prune_at = CASE WHEN prune_interval_hours = 0 THEN next_prune_at
                        ELSE CURRENT_TIMESTAMP + prune_interval_hours * INTERVAL '1 hour' END,
                    error_code = NULL
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value()).update();
        attempts.terminal(work, "SUCCEEDED", null, null, null);
    }

    public void terminal(Work work, String status, @Nullable String code, @Nullable String errorMessage,
            @Nullable String technicalDetail) {
        attempts.terminal(work, status, code, errorMessage, technicalDetail);
        closeRun(work, "FAILED".equals(status) ? "FAILED" : "CANCELLED", code);
        if (code != null) {
            jdbc.sql("""
                    UPDATE sharepoint_sources SET error_code = :code,
                        next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute',
                        next_prune_at = CASE WHEN prune_interval_hours > 0 AND next_prune_at <= CURRENT_TIMESTAMP
                            THEN CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute' ELSE next_prune_at END
                    WHERE tenant_id = :tenant AND source_id = :source AND scope_revision = :scope
                      AND generation = :generation
                    """).param("code", code).param("tenant", work.tenantId().value())
                    .param("source", work.sourceId().value()).param("scope", work.scopeRevision())
                    .param("generation", work.generation()).update();
        }
    }

    public void continuation(Work work, @Nullable String errorCode) {
        attempts.continuation(work, errorCode);
    }

    public void retry(Work work, String error) {
        jdbc.sql("""
                UPDATE source_sync_attempts SET failure_attempts = failure_attempts + 1,
                  status = CASE WHEN failure_attempts >= 5 THEN 'FAILED' ELSE 'NOT_STARTED' END,
                  completed_at = CASE WHEN failure_attempts >= 5 THEN CURRENT_TIMESTAMP ELSE NULL END,
                  claim_token = NULL, lease_expires_at = NULL, delivery_id = NULL, dispatch_token = NULL,
                  dispatch_lease_expires_at = NULL, redis_message_id = NULL, dispatched_at = NULL,
                  next_dispatch_at = CURRENT_TIMESTAMP + INTERVAL '30 seconds', error_code = :error
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """).param("error", WorkLeases.safeErrorCode(error)).param("tenant", work.tenantId().value())
                .param("id", work.operationId().value()).param("token", work.claimToken()).update();
        boolean exhausted = jdbc.sql("SELECT status = 'FAILED' FROM source_sync_attempts WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .query(Boolean.class).optional().orElse(false);
        if (exhausted) closeRun(work, "FAILED", error);
        jdbc.sql("""
                UPDATE sharepoint_sources SET error_code = :code,
                    next_sync_at = CASE WHEN :exhausted
                        THEN CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute' ELSE next_sync_at END,
                    next_prune_at = CASE WHEN :exhausted AND prune_interval_hours > 0
                            AND next_prune_at <= CURRENT_TIMESTAMP
                        THEN CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute' ELSE next_prune_at END
                WHERE tenant_id = :tenant AND source_id = :source AND scope_revision = :scope AND generation = :generation
                """).param("code", WorkLeases.safeErrorCode(error)).param("exhausted", exhausted)
                .param("tenant", work.tenantId().value())
                .param("source", work.sourceId().value()).param("scope", work.scopeRevision())
                .param("generation", work.generation()).update();
    }

    private Run run(java.sql.ResultSet r, int ignored) throws java.sql.SQLException {
        return new Run(new TenantId(r.getObject("tenant_id", UUID.class)), r.getObject("id", UUID.class),
                new SourceId(r.getObject("source_id", UUID.class)), r.getString("kind"),
                r.getTimestamp("window_start") == null ? null : r.getTimestamp("window_start").toInstant(),
                r.getTimestamp("window_end").toInstant(), r.getString("checkpoint_drive_id"),
                r.getString("checkpoint_site_id"), r.getString("checkpoint_link"), r.getString("checkpoint_folder_id"),
                folders(r.getString("checkpoint_folders")), r.getBoolean("listing_complete"));
    }

    private static List<String> folders(@Nullable String stored) {
        return stored == null || stored.isEmpty() ? List.of() : List.of(stored.split("\n"));
    }

    public record DueSource(TenantId tenantId, SourceId sourceId, boolean prune) {}

    public record SourceState(String scopeMode, boolean includeDocuments, boolean includePages,
                              int pruneIntervalHours, @Nullable Instant refreshWindowEnd, @Nullable String tenantHost,
                              boolean pruneDue) {}

    public record Run(TenantId tenantId, UUID id, SourceId sourceId, String kind, @Nullable Instant windowStart,
                      Instant windowEnd, @Nullable String checkpointDriveId, @Nullable String checkpointSiteId,
                      @Nullable String checkpointLink, @Nullable String checkpointFolderId,
                      List<String> checkpointFolders, boolean listingComplete) {
        public boolean prune() { return "PRUNE".equals(kind); }
    }

    public record Missing(SourceItemId itemId, String providerFileId) {}
}
