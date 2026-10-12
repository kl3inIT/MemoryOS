package io.memoryos.connector.lark.persistence;

import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.DueSource;
import io.memoryos.connector.sync.persistence.WorkLeases;
import io.memoryos.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The run of a Lark Source: where its walk stands and which objects it has listed. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcLarkSyncRepository {
    private final JdbcClient jdbc;

    public JdbcLarkSyncRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Sources whose run is due, oldest schedule first, skipping those already running. */
    public List<DueSource> due(int limit) {
        return jdbc.sql("""
                SELECT s.tenant_id, s.source_id
                FROM lark_sources s
                JOIN source_sync_state y ON y.tenant_id = s.tenant_id AND y.source_id = s.source_id
                JOIN connector_credential_pairs p ON p.tenant_id = s.tenant_id AND p.id = s.source_id
                JOIN tenants t ON t.id = s.tenant_id
                WHERE t.status = 'ACTIVE' AND p.status NOT IN ('DELETING', 'PAUSED') AND NOT y.sync_paused
                  AND y.next_sync_at <= CURRENT_TIMESTAMP
                  AND EXISTS (SELECT 1 FROM lark_roots r
                      WHERE r.tenant_id = s.tenant_id AND r.source_id = s.source_id)
                  AND NOT EXISTS (SELECT 1 FROM source_sync_attempts a
                      WHERE a.tenant_id = s.tenant_id AND a.source_id = s.source_id
                        AND a.status IN ('NOT_STARTED', 'IN_PROGRESS'))
                ORDER BY y.next_sync_at, s.source_id LIMIT :limit
                """).param("limit", Math.clamp(limit, 1, 32))
                .query((r, _) -> new DueSource(new TenantId(r.getObject("tenant_id", UUID.class)),
                        new SourceId(r.getObject("source_id", UUID.class)))).list();
    }

    /** The run a previous delivery of this attempt left unfinished. */
    public Optional<Run> openRun(Work work) {
        return jdbc.sql("""
                SELECT * FROM lark_sync_runs
                WHERE tenant_id = :tenant AND source_sync_attempt_id = :attempt AND status = 'IN_PROGRESS'
                """).param("tenant", work.tenantId().value()).param("attempt", work.operationId().value())
                .query(this::run).optional();
    }

    public Run startRun(Work work) {
        // The caller holds the current attempt, so a run another attempt left open (cancelled, superseded or
        // abandoned by a worker) can no longer finish and must not block this one.
        jdbc.sql("""
                UPDATE lark_sync_runs SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND source_id = :source AND status = 'IN_PROGRESS'
                  AND source_sync_attempt_id IS DISTINCT FROM :attempt
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("attempt", work.operationId().value()).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO lark_sync_runs (id, tenant_id, source_id, source_sync_attempt_id)
                VALUES (:id, :tenant, :source, :attempt)
                """).param("id", id).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("attempt", work.operationId().value()).update();
        return jdbc.sql("SELECT * FROM lark_sync_runs WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", work.tenantId().value()).param("id", id).query(this::run).single();
    }

    /** Records where the walk stands: the root, the container being listed, its page and the containers waiting. */
    public void checkpoint(Run run, int root, @Nullable String container, @Nullable String page,
            List<String> pending) {
        jdbc.sql("""
                UPDATE lark_sync_runs SET checkpoint_root = :root, checkpoint_container = :container,
                    checkpoint_page = :page, checkpoint_pending = :pending
                WHERE tenant_id = :tenant AND id = :id AND status = 'IN_PROGRESS'
                """).param("root", root).param("container", container).param("page", page)
                .param("pending", pending.isEmpty() ? null : String.join("\n", pending))
                .param("tenant", run.tenantId().value()).param("id", run.id()).update();
    }

    /** Closes the run of an attempt that ended without completing it. */
    public void closeRun(Work work, String status, @Nullable String code) {
        jdbc.sql("""
                UPDATE lark_sync_runs SET status = :status, error_code = :code, completed_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND source_sync_attempt_id = :attempt AND status = 'IN_PROGRESS'
                """).param("status", status).param("code", code == null ? null : WorkLeases.safeErrorCode(code))
                .param("tenant", work.tenantId().value()).param("attempt", work.operationId().value()).update();
    }

    /** Records that the run listed every root to its end, which is what allows it to remove anything. */
    public void listingComplete(Run run) {
        jdbc.sql("""
                UPDATE lark_sync_runs SET listing_complete = TRUE
                WHERE tenant_id = :tenant AND id = :id AND status = 'IN_PROGRESS'
                """).param("tenant", run.tenantId().value()).param("id", run.id()).update();
    }

    public void completeRun(Run run) {
        jdbc.sql("""
                UPDATE lark_sync_runs SET status = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND id = :id AND status = 'IN_PROGRESS'
                """).param("tenant", run.tenantId().value()).param("id", run.id()).update();
    }

    /** Remembers an object the run listed. */
    public void observe(Run run, String providerFileId, String objectType, @Nullable String name,
            @Nullable String contentVersion) {
        jdbc.sql("""
                INSERT INTO lark_items (tenant_id, source_id, provider_file_id, object_type, name, content_version,
                    last_seen_at, last_seen_run)
                VALUES (:tenant, :source, :file, :type, :name, :version, CURRENT_TIMESTAMP, :run)
                ON CONFLICT (tenant_id, source_id, provider_file_id) DO UPDATE SET object_type = EXCLUDED.object_type,
                    name = EXCLUDED.name, content_version = EXCLUDED.content_version,
                    last_seen_at = CURRENT_TIMESTAMP, last_seen_run = EXCLUDED.last_seen_run
                """).param("tenant", run.tenantId().value()).param("source", run.sourceId().value())
                .param("file", providerFileId).param("type", objectType)
                .param("name", name == null ? null : name.length() > 400 ? name.substring(0, 400) : name)
                .param("version", contentVersion).param("run", run.id()).update();
    }

    public void forget(TenantId tenant, SourceId source, String providerFileId) {
        jdbc.sql("""
                DELETE FROM lark_items
                WHERE tenant_id = :tenant AND source_id = :source AND provider_file_id = :file
                """).param("tenant", tenant.value()).param("source", source.value()).param("file", providerFileId).update();
    }

    /** Items the Source holds that the finished listing did not see, in bounded batches. */
    public List<Missing> missing(Run run) {
        return jdbc.sql("""
                SELECT i.id, i.provider_file_id FROM connector_items i
                JOIN connector_credential_pairs p ON p.tenant_id = i.tenant_id AND p.connector_id = i.connector_id
                LEFT JOIN lark_items s ON s.tenant_id = p.tenant_id AND s.source_id = p.id
                    AND s.provider_file_id = i.provider_file_id
                WHERE p.tenant_id = :tenant AND p.id = :source AND i.status <> 'DELETING'
                  AND (s.provider_file_id IS NULL OR s.last_seen_run IS DISTINCT FROM :run)
                ORDER BY i.id LIMIT 32
                """).param("tenant", run.tenantId().value()).param("source", run.sourceId().value())
                .param("run", run.id())
                .query((r, _) -> new Missing(new SourceItemId(r.getObject("id", UUID.class)),
                        r.getString("provider_file_id"))).list();
    }

    private Run run(ResultSet r, int ignored) throws SQLException {
        String pending = r.getString("checkpoint_pending");
        return new Run(new TenantId(r.getObject("tenant_id", UUID.class)), r.getObject("id", UUID.class),
                new SourceId(r.getObject("source_id", UUID.class)), r.getInt("checkpoint_root"),
                r.getString("checkpoint_container"), r.getString("checkpoint_page"),
                pending == null || pending.isEmpty() ? List.of() : List.of(pending.split("\n")),
                r.getBoolean("listing_complete"));
    }

    public record Run(TenantId tenantId, UUID id, SourceId sourceId, int checkpointRoot,
                      @Nullable String checkpointContainer, @Nullable String checkpointPage,
                      List<String> checkpointPending, boolean listingComplete) {}

    public record Missing(SourceItemId itemId, String providerFileId) {}
}
