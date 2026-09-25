package io.memoryos.connector.sharepoint.persistence;

import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.DueSource;
import io.memoryos.connector.sync.persistence.WorkLeases;
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
 * The SharePoint walk of a synchronization run. A refresh reads the change log since the last successful
 * window; a prune lists the whole scope and removes what it no longer finds, but only when the listing
 * completed. The attempt itself is {@code JdbcSourceSyncRepository}'s.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSharePointSyncRepository {
    /** Repeats part of the previous window so a change written during it is not missed. */
    public static final java.time.Duration OVERLAP = java.time.Duration.ofMinutes(30);

    private final JdbcClient jdbc;

    public JdbcSharePointSyncRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Sources whose refresh or prune is due, oldest schedule first, skipping those already running. */
    public List<DueSource> due(int limit) {
        return jdbc.sql("""
                SELECT s.tenant_id, s.source_id
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
                        new SourceId(r.getObject("source_id", UUID.class)))).list();
    }

    /** A due prune waits for the next refresh slot instead of being retried on every tick. */
    public void postponePrune(TenantId tenant, SourceId source) {
        jdbc.sql("""
                UPDATE sharepoint_sources
                SET next_prune_at = CASE WHEN prune_interval_hours > 0 AND next_prune_at <= CURRENT_TIMESTAMP
                        THEN CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute' ELSE next_prune_at END
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
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

    /** The run a previous delivery of this attempt left unfinished. */
    public Optional<Run> openRun(Work work) {
        return jdbc.sql("""
                SELECT * FROM sharepoint_sync_runs
                WHERE tenant_id = :tenant AND source_sync_attempt_id = :attempt AND status = 'IN_PROGRESS'
                """).param("tenant", work.tenantId().value()).param("attempt", work.operationId().value())
                .query(this::run).optional();
    }

    /**
     * Opens the run for this attempt. The window ends at the database's clock, so the next refresh continues from
     * a time every worker agrees on.
     */
    public Run startRun(Work work, String kind, @Nullable Instant previousWindowEnd) {
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
                VALUES (:id, :tenant, :source, :attempt, :kind,
                    CAST(:previous AS TIMESTAMPTZ) - :overlapMinutes * INTERVAL '1 minute', CURRENT_TIMESTAMP)
                """).param("id", id).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("attempt", work.operationId().value()).param("kind", kind)
                .param("previous", previousWindowEnd == null ? null : WorkLeases.sqlTime(previousWindowEnd))
                .param("overlapMinutes", OVERLAP.toMinutes()).update();
        return jdbc.sql("SELECT * FROM sharepoint_sync_runs WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", work.tenantId().value()).param("id", id).query(this::run).single();
    }

    /** Records the libraries and sites the run covers, and the items an earlier run failed on. */
    public void resolveScope(Run run, List<Target> drives, List<String> sites, List<String> retries,
            SourceId source) {
        int position = 0;
        for (var drive : drives) {
            scopeRow(run, "DRIVE", position++, drive.driveId(), drive.siteId(), drive.itemId());
        }
        position = 0;
        for (var site : sites) scopeRow(run, "SITE", position++, null, site, null);
        if (!retries.isEmpty()) {
            jdbc.sql("""
                    INSERT INTO sharepoint_sync_run_scope (tenant_id, run_id, kind, position, drive_id, site_id, item_id)
                    SELECT i.tenant_id, :run, 'RETRY', CAST(row_number() OVER (ORDER BY i.provider_file_id) AS INTEGER),
                        i.drive_id, i.site_id, i.provider_file_id
                    FROM sharepoint_items i
                    WHERE i.tenant_id = :tenant AND i.source_id = :source AND i.provider_file_id IN (:files)
                    """).param("run", run.id()).param("tenant", run.tenantId().value())
                    .param("source", source.value()).param("files", retries).update();
        }
        jdbc.sql("UPDATE sharepoint_sync_runs SET scope_resolved = TRUE WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", run.tenantId().value()).param("id", run.id()).update();
    }

    private void scopeRow(Run run, String kind, int position, @Nullable String drive, @Nullable String site,
            @Nullable String item) {
        jdbc.sql("""
                INSERT INTO sharepoint_sync_run_scope (tenant_id, run_id, kind, position, drive_id, site_id, item_id)
                VALUES (:tenant, :run, :kind, :position, :drive, :site, :item)
                """).param("tenant", run.tenantId().value()).param("run", run.id()).param("kind", kind)
                .param("position", position).param("drive", drive).param("site", site).param("item", item).update();
    }

    public List<Target> drives(Run run) {
        return jdbc.sql("""
                SELECT drive_id, site_id, item_id FROM sharepoint_sync_run_scope
                WHERE tenant_id = :tenant AND run_id = :run AND kind = 'DRIVE' ORDER BY position
                """).param("tenant", run.tenantId().value()).param("run", run.id())
                .query((r, _) -> new Target(r.getString("drive_id"), r.getString("site_id"), r.getString("item_id")))
                .list();
    }

    public List<String> sites(Run run) {
        return jdbc.sql("""
                SELECT site_id FROM sharepoint_sync_run_scope
                WHERE tenant_id = :tenant AND run_id = :run AND kind = 'SITE' ORDER BY position
                """).param("tenant", run.tenantId().value()).param("run", run.id()).query(String.class).list();
    }

    /** Items an earlier run failed on that this run has not retried yet. */
    public List<Retry> retries(Run run) {
        return jdbc.sql("""
                SELECT position, drive_id, site_id, item_id FROM sharepoint_sync_run_scope
                WHERE tenant_id = :tenant AND run_id = :run AND kind = 'RETRY' ORDER BY position
                """).param("tenant", run.tenantId().value()).param("run", run.id())
                .query((r, _) -> new Retry(r.getInt("position"), r.getString("drive_id"), r.getString("site_id"),
                        r.getString("item_id"))).list();
    }

    public void retried(Run run, Retry retry) {
        jdbc.sql("""
                DELETE FROM sharepoint_sync_run_scope
                WHERE tenant_id = :tenant AND run_id = :run AND kind = 'RETRY' AND position = :position
                """).param("tenant", run.tenantId().value()).param("run", run.id())
                .param("position", retry.position()).update();
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
    public void closeRun(Work work, String status, @Nullable String code) {
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

    public void completeRun(Run run) {
        jdbc.sql("""
                UPDATE sharepoint_sync_runs SET status = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND id = :id AND status = 'IN_PROGRESS'
                """).param("tenant", run.tenantId().value()).param("id", run.id()).update();
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

    /** A completed refresh records the window its next run continues from. */
    public void finishRefresh(Run run) {
        jdbc.sql("""
                UPDATE sharepoint_sources SET refresh_window_end = :end
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("end", WorkLeases.sqlTime(run.windowEnd())).param("tenant", run.tenantId().value())
                .param("source", run.sourceId().value()).update();
    }

    public void finishPrune(Run run) {
        jdbc.sql("""
                UPDATE sharepoint_sources SET last_pruned_at = CURRENT_TIMESTAMP,
                    next_prune_at = CASE WHEN prune_interval_hours = 0 THEN next_prune_at
                        ELSE CURRENT_TIMESTAMP + prune_interval_hours * INTERVAL '1 hour' END
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", run.tenantId().value()).param("source", run.sourceId().value()).update();
    }

    private Run run(java.sql.ResultSet r, int ignored) throws java.sql.SQLException {
        return new Run(new TenantId(r.getObject("tenant_id", UUID.class)), r.getObject("id", UUID.class),
                new SourceId(r.getObject("source_id", UUID.class)), r.getString("kind"),
                r.getTimestamp("window_start") == null ? null : r.getTimestamp("window_start").toInstant(),
                r.getTimestamp("window_end").toInstant(), r.getString("checkpoint_drive_id"),
                r.getString("checkpoint_site_id"), r.getString("checkpoint_link"), r.getString("checkpoint_folder_id"),
                folders(r.getString("checkpoint_folders")), r.getBoolean("listing_complete"),
                r.getBoolean("scope_resolved"));
    }

    private static List<String> folders(@Nullable String stored) {
        return stored == null || stored.isEmpty() ? List.of() : List.of(stored.split("\n"));
    }

    public record SourceState(String scopeMode, boolean includeDocuments, boolean includePages,
                              int pruneIntervalHours, @Nullable Instant refreshWindowEnd, @Nullable String tenantHost,
                              boolean pruneDue) {}

    public record Run(TenantId tenantId, UUID id, SourceId sourceId, String kind, @Nullable Instant windowStart,
                      Instant windowEnd, @Nullable String checkpointDriveId, @Nullable String checkpointSiteId,
                      @Nullable String checkpointLink, @Nullable String checkpointFolderId,
                      List<String> checkpointFolders, boolean listingComplete, boolean scopeResolved) {
        public boolean prune() { return "PRUNE".equals(kind); }
    }

    /** A library the run walks; {@code itemId} is set when the root is a folder inside it. */
    public record Target(String driveId, @Nullable String siteId, @Nullable String itemId) {}

    /** An item an earlier run failed on: a file in {@code driveId}, or a page of {@code siteId}. */
    public record Retry(int position, @Nullable String driveId, @Nullable String siteId, String providerFileId) {}

    public record Missing(SourceItemId itemId, String providerFileId) {}
}
