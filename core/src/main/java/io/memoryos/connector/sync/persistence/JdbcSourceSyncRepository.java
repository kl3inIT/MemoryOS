package io.memoryos.connector.sync.persistence;

import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.SourceRunTrigger;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Synchronization attempts of every connector: queueing, the claim fence, the run's file outcomes and item
 * errors, and how an attempt ends. Provider traversal state lives in each provider's own repository; the
 * provider Source and credential rows an attempt is fenced against are named by a {@link SyncTarget}.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceSyncRepository {
    /** The run counters, in the order {@link #ZERO_COUNTERS} fills them. */
    public static final String COUNTERS = """
            scanned, acquired, unchanged, already_pending, acquisition_failed, skipped, removed,
            published, indexing_pending, indexing_failed, indexing_superseded, indexing_cancelled""";
    private static final String ZERO_COUNTERS = "0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0";
    /** A run is aborted once more than this many items failed, and more than a tenth of what it processed. */
    public static final int ITEM_FAILURE_FLOOR = 3;
    private static final String TABLE = "source_sync_attempts";

    private final JdbcClient jdbc;

    public JdbcSourceSyncRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Queues a run, or returns the one already queued or running for the Source. */
    public SourceOperationView enqueue(SyncTarget target, TenantId tenant, SourceId source, long credentialRevision,
            SourceRunTrigger trigger, @Nullable ActorId actor) {
        var live = live(tenant, source);
        if (live.isPresent()) return live.get();
        UUID id = UUID.randomUUID();
        var trace = SourceOperationTraceContext.current();
        jdbc.sql("UPDATE " + target.sourceTable() + """
                 SET generation = generation + 1,
                    next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute'
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
        clearSyncError(tenant, source);
        jdbc.sql("""
                INSERT INTO source_sync_attempts (id, tenant_id, source_id, scope_revision, credential_revision,
                    generation, origin_trace_id, origin_span_id, history_version, trigger_kind, actor_id,
                """ + COUNTERS + ")\nSELECT :id, s.tenant_id, s.source_id, s." + target.scopeRevisionColumn()
                + ", :credential, s.generation, :trace, :span, 1, :trigger, :actor, " + ZERO_COUNTERS
                + "\nFROM " + target.sourceTable() + " s WHERE s.tenant_id = :tenant AND s.source_id = :source")
                .param("id", id).param("tenant", tenant.value()).param("source", source.value())
                .param("credential", credentialRevision).param("trace", trace == null ? null : trace.traceId())
                .param("span", trace == null ? null : trace.spanId()).param("trigger", trigger.name())
                .param("actor", actor == null ? null : actor.value()).update();
        return find(tenant, new SourceOperationId(id)).orElseThrow();
    }

    public Optional<SourceOperationView> live(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT * FROM source_sync_attempts WHERE tenant_id = :tenant AND source_id = :source
                  AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                """).param("tenant", tenant.value()).param("source", source.value()).query(this::operation).optional();
    }

    public Optional<SourceOperationView> find(TenantId tenant, SourceOperationId id) {
        return jdbc.sql("SELECT * FROM source_sync_attempts WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.value()).param("id", id.value()).query(this::operation).optional();
    }

    public boolean automaticSyncEnabled(SyncTarget target, TenantId tenant, SourceId source) {
        return jdbc.sql("SELECT NOT sync_paused FROM " + target.sourceTable()
                        + " WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value())
                .query(Boolean.class).optional().orElse(false);
    }

    /** Moves the next scheduled run one interval ahead. */
    public void postpone(SyncTarget target, TenantId tenant, SourceId source) {
        jdbc.sql("UPDATE " + target.sourceTable() + """
                 SET next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute'
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    public Optional<Work> claim(TenantId tenant, SourceOperationId id, UUID delivery) {
        return WorkLeases.claim(jdbc, TABLE, tenant.value(), id.value(), delivery, (operation, token) ->
                jdbc.sql("SELECT * FROM source_sync_attempts WHERE id = :id AND claim_token = :token")
                        .param("id", operation).param("token", token).query((r, _) -> new Work(tenant,
                                new SourceId(r.getObject("source_id", UUID.class)), id, token,
                                r.getLong("scope_revision"), r.getLong("credential_revision"), r.getLong("generation"),
                                WorkLeases.initialQueueWait(r))).single());
    }

    public boolean renew(Work work) {
        return WorkLeases.renew(jdbc, TABLE, work.tenantId().value(), work.operationId().value(), work.claimToken());
    }

    /**
     * Whether the claim may still write: it holds a live lease, the Source still has the scope, generation and
     * usable credential revision the attempt captured, and neither the Source nor its Tenant stopped. Locks the
     * attempt and the provider Source row; the caller already holds the Source lock.
     */
    public boolean current(SyncTarget target, Work work) {
        return jdbc.sql("""
                SELECT a.id FROM source_sync_attempts a
                JOIN connector_credential_pairs p ON p.tenant_id = a.tenant_id AND p.id = a.source_id
                JOIN tenants t ON t.id = a.tenant_id
                JOIN credentials c ON c.tenant_id = p.tenant_id AND c.id = p.credential_id
                """ + "JOIN " + target.sourceTable() + " s ON s.tenant_id = a.tenant_id AND s.source_id = a.source_id\n"
                + "JOIN " + target.credentialTable() + " pc ON pc.tenant_id = c.tenant_id AND pc.credential_id = c.id\n"
                + """
                WHERE a.tenant_id = :tenant AND a.id = :id AND a.claim_token = :token
                  AND a.status = 'IN_PROGRESS' AND a.lease_expires_at > CURRENT_TIMESTAMP
                  AND s.generation = a.generation AND p.status NOT IN ('DELETING', 'PAUSED') AND t.status = 'ACTIVE'
                  AND c.status = 'ACTIVE' AND pc.connection_status = 'ACTIVE'
                  AND pc.credential_revision = a.credential_revision
                """ + "  AND s." + target.scopeRevisionColumn() + " = a.scope_revision\nFOR UPDATE OF a, s")
                .param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("token", work.claimToken()).query(UUID.class).optional().isPresent();
    }

    /**
     * Hands a run that made progress back to the dispatcher for its next slice. Progress restores the whole
     * retry budget, as a long run otherwise spends it on unrelated transient failures.
     */
    public void continuation(Work work) {
        jdbc.sql("UPDATE source_sync_attempts SET status = 'NOT_STARTED', failure_attempts = 0, error_code = NULL, "
                        + WorkLeases.RELEASE + """
                ,
                    next_dispatch_at = CURRENT_TIMESTAMP + INTERVAL '1 second'
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("token", work.claimToken()).update();
    }

    /**
     * Schedules the attempt again after {@code backoff}, keeping its checkpoint and its error evidence, or fails
     * it once its failures reach {@code maxAttempts}.
     */
    public WorkLeases.RetryOutcome retry(Work work, String code, @Nullable String message, @Nullable String detail,
            int maxAttempts, Duration backoff) {
        return WorkLeases.retry(jdbc, TABLE, WorkLeases.AttemptCount.failures("failure_attempts"),
                work.tenantId().value(), work.operationId().value(), work.claimToken(), code, message, detail,
                maxAttempts, backoff);
    }

    /** Starts counting item failures afresh, when an aborted attempt is retried. */
    public void restartFailureWindow(Work work) {
        jdbc.sql("""
                UPDATE source_sync_attempts SET failure_window_scanned = scanned,
                    failure_window_failed = acquisition_failed
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("token", work.claimToken()).update();
    }

    /**
     * Ends the attempt with {@code status}. A failure is recorded on the Source, whose next scheduled run then
     * waits one interval. Returns false when the claim was already lost.
     */
    public boolean terminal(SyncTarget target, Work work, String status, @Nullable String code,
            @Nullable String errorMessage, @Nullable String errorDetail) {
        int updated = jdbc.sql("""
                UPDATE source_sync_attempts SET status = :status, error_code = :code, completed_at = CURRENT_TIMESTAMP,
                    error_message = :errorMessage, error_detail = :errorDetail,
                    claim_token = NULL, lease_expires_at = NULL
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token
                  AND status = 'IN_PROGRESS' AND lease_expires_at > CURRENT_TIMESTAMP
                """).param("status", status).param("code", code)
                .param("errorMessage", WorkLeases.safeErrorMessage(errorMessage))
                .param("errorDetail", WorkLeases.safeErrorDetail(errorDetail))
                .param("tenant", work.tenantId().value())
                .param("id", work.operationId().value()).param("token", work.claimToken()).update();
        if (updated == 1 && "FAILED".equals(status) && code != null) recordFailure(target, work, code);
        return updated == 1;
    }

    /** Records a failed attempt on the Source it still belongs to, and waits one interval before the next run. */
    public void recordFailure(SyncTarget target, Work work, String code) {
        int current = jdbc.sql("UPDATE " + target.sourceTable() + """
                 SET next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute'
                WHERE tenant_id = :tenant AND source_id = :source AND generation = :generation
                """ + "  AND " + target.scopeRevisionColumn() + " = :scope")
                .param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("scope", work.scopeRevision()).param("generation", work.generation()).update();
        if (current == 1) {
            jdbc.sql("""
                    UPDATE connector_credential_pairs SET sync_error_code = :code
                    WHERE tenant_id = :tenant AND id = :source
                    """).param("code", WorkLeases.safeErrorCode(code)).param("tenant", work.tenantId().value())
                    .param("source", work.sourceId().value()).update();
        }
    }

    /**
     * Completes the attempt: {@code COMPLETED_WITH_ERRORS} when any item failed or was skipped with an error,
     * otherwise {@code SUCCEEDED}.
     * Either way the Source synchronized, so its error clears and its next run is one interval away.
     */
    public Optional<String> complete(SyncTarget target, Work work) {
        var status = jdbc.sql("""
                UPDATE source_sync_attempts a SET
                    status = CASE WHEN acquisition_failed > 0 OR EXISTS (
                        SELECT 1 FROM source_run_errors e WHERE e.tenant_id = a.tenant_id AND e.run_id = a.id
                          AND e.resolved_at IS NULL
                          AND (e.error_key LIKE 'FILE:%' OR e.error_key LIKE 'FOLDER:%' OR e.error_key LIKE 'PAGE:%'))
                        THEN 'COMPLETED_WITH_ERRORS' ELSE 'SUCCEEDED' END,
                    error_code = NULL, error_message = NULL, error_detail = NULL,
                    completed_at = CURRENT_TIMESTAMP, claim_token = NULL, lease_expires_at = NULL
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token
                  AND status = 'IN_PROGRESS' AND lease_expires_at > CURRENT_TIMESTAMP
                RETURNING status
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("token", work.claimToken()).query(String.class).optional();
        if (status.isEmpty()) return status;
        jdbc.sql("UPDATE " + target.sourceTable() + """
                 SET last_synced_at = CURRENT_TIMESTAMP,
                    next_sync_at = CURRENT_TIMESTAMP + sync_interval_minutes * INTERVAL '1 minute'
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value()).update();
        clearSyncError(work.tenantId(), work.sourceId());
        return status;
    }

    public void clearSyncError(TenantId tenant, SourceId source) {
        jdbc.sql("""
                UPDATE connector_credential_pairs SET sync_error_code = NULL
                WHERE tenant_id = :tenant AND id = :source AND sync_error_code IS NOT NULL
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    /** Ends queued and running attempts that a newer scope or credential replaced. */
    public void supersede(TenantId tenant, SourceId source) {
        end(tenant, source, "SUPERSEDED", null);
    }

    /**
     * Cancels queued and running attempts because the Source stopped: {@code SOURCE_DELETING} when it is being
     * deleted, {@code SOURCE_PAUSED} when its synchronization was paused. A running attempt notices at its next
     * fence and writes nothing more.
     */
    public void cancel(TenantId tenant, SourceId source, String code) {
        end(tenant, source, "CANCELLED", WorkLeases.safeErrorCode(code));
    }

    private void end(TenantId tenant, SourceId source, String status, @Nullable String code) {
        jdbc.sql("UPDATE source_sync_attempts SET status = :status, error_code = :code, completed_at = CURRENT_TIMESTAMP, "
                        + WorkLeases.RELEASE + """

                WHERE tenant_id = :tenant AND source_id = :source AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                """).param("status", status).param("code", code)
                .param("tenant", tenant.value()).param("source", source.value()).update();
    }

    /**
     * Cancels queued (never-claimed) runs for a paused Source. In-flight runs are left to settle through the
     * {@link #current} fence so they record {@code CANCELLED} at a safe boundary.
     */
    public void cancelQueuedForPause(TenantId tenant, SourceId source) {
        jdbc.sql("UPDATE source_sync_attempts SET status = 'CANCELLED', error_code = 'SOURCE_PAUSED', "
                        + "completed_at = CURRENT_TIMESTAMP, " + WorkLeases.RELEASE + """

                WHERE tenant_id = :tenant AND source_id = :source AND status = 'NOT_STARTED'
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    /** Counts a file the run observed, once per run however often it is listed. */
    public void observed(Work work, String file, @Nullable String name) {
        jdbc.sql("""
                WITH inserted AS (
                    INSERT INTO source_run_files (tenant_id, run_id, file_id, file_name)
                    VALUES (:tenant, :id, :file, :name) ON CONFLICT DO NOTHING RETURNING 1
                )
                UPDATE source_sync_attempts SET scanned = scanned + (SELECT COUNT(*) FROM inserted)
                WHERE tenant_id = :tenant AND id = :id
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("file", file).param("name", name == null ? null : truncate(name, 255)).update();
    }

    /** Records how an observed file ended in this run; the first outcome is the one counted. */
    public void outcome(Work work, String file, FileOutcome outcome, boolean alreadyPending) {
        jdbc.sql("""
                WITH changed AS (
                    UPDATE source_run_files SET outcome = :outcome
                    WHERE tenant_id = :tenant AND run_id = :id AND file_id = :file AND outcome IS NULL RETURNING 1
                )
                UPDATE source_sync_attempts SET
                    acquired = acquired + CASE WHEN :outcome = 'ACQUIRED' THEN (SELECT COUNT(*) FROM changed) ELSE 0 END,
                    unchanged = unchanged + CASE WHEN :outcome = 'UNCHANGED' THEN (SELECT COUNT(*) FROM changed) ELSE 0 END,
                    already_pending = already_pending + CASE WHEN :pending THEN (SELECT COUNT(*) FROM changed) ELSE 0 END,
                    skipped = skipped + CASE WHEN :outcome = 'SKIPPED' THEN (SELECT COUNT(*) FROM changed) ELSE 0 END
                WHERE tenant_id = :tenant AND id = :id
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("file", file).param("outcome", outcome.name()).param("pending", alreadyPending).update();
    }

    public void removed(Work work, int count) {
        if (count == 0) return;
        jdbc.sql("UPDATE source_sync_attempts SET removed = removed + :count WHERE tenant_id = :tenant AND id = :id")
                .param("count", count).param("tenant", work.tenantId().value())
                .param("id", work.operationId().value()).update();
    }

    /**
     * Records an item failure as a run error and counts it, once per item and run. A skipped item (one the
     * provider cannot supply in a supported form) is counted as skipped and never aborts the run. Returns true
     * when failures since the attempt last started over exceed {@link #ITEM_FAILURE_FLOOR} and a tenth of the
     * files processed, which aborts the run.
     */
    public boolean itemFailed(Work work, ItemFailure failure) {
        jdbc.sql("""
                INSERT INTO source_run_errors (id, tenant_id, run_id, error_key, operation_id, file_id, file_name,
                    stage, code, error_message, error_detail)
                VALUES (:errorId, :tenant, :id, :key, :id, :file,
                    COALESCE(:name, (SELECT file_name FROM source_run_files
                        WHERE tenant_id = :tenant AND run_id = :id AND file_id = :file)),
                    source_run_error_stage(:code), :code, :message, :detail)
                ON CONFLICT (tenant_id, run_id, error_key) DO UPDATE SET code = EXCLUDED.code,
                    stage = EXCLUDED.stage, error_message = EXCLUDED.error_message,
                    error_detail = EXCLUDED.error_detail, occurred_at = CURRENT_TIMESTAMP,
                    resolved_at = NULL, resolved_by_run_id = NULL
                """).param("errorId", UUID.randomUUID()).param("tenant", work.tenantId().value())
                .param("id", work.operationId().value()).param("key", failure.key())
                .param("file", failure.fileId())
                .param("name", failure.fileName() == null ? null : truncate(failure.fileName(), 255))
                .param("code", WorkLeases.safeErrorCode(failure.code()))
                .param("message", WorkLeases.safeErrorMessage(failure.message()))
                .param("detail", WorkLeases.safeErrorDetail(failure.detail())).update();
        return jdbc.sql("""
                WITH changed AS (
                    INSERT INTO source_run_files (tenant_id, run_id, file_id, file_name, outcome)
                    VALUES (:tenant, :id, :file, :name, :outcome)
                    ON CONFLICT (tenant_id, run_id, file_id) DO UPDATE SET outcome = EXCLUDED.outcome
                        WHERE source_run_files.outcome IS NULL
                    RETURNING 1
                )
                UPDATE source_sync_attempts SET
                    acquisition_failed = acquisition_failed + CASE WHEN :skipped THEN 0 ELSE (SELECT COUNT(*) FROM changed) END,
                    skipped = skipped + CASE WHEN :skipped THEN (SELECT COUNT(*) FROM changed) ELSE 0 END
                WHERE tenant_id = :tenant AND id = :id
                RETURNING acquisition_failed - failure_window_failed > :floor
                    AND (acquisition_failed - failure_window_failed) * 10 > scanned - failure_window_scanned
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("file", failure.fileId())
                .param("name", failure.fileName() == null ? null : truncate(failure.fileName(), 255))
                .param("outcome", failure.skipped() ? "SKIPPED" : "FAILED").param("skipped", failure.skipped())
                .param("floor", ITEM_FAILURE_FLOOR).query(Boolean.class).optional().orElse(false);
    }

    /**
     * Provider identifiers of the Source's items whose acquisition error from an earlier run is still
     * unresolved, which the next run retries.
     */
    public List<String> unresolvedItems(TenantId tenant, SourceId source, @Nullable SourceOperationId except,
            int limit) {
        return jdbc.sql("""
                SELECT DISTINCT e.file_id FROM source_run_errors e
                JOIN source_sync_attempts r ON r.tenant_id = e.tenant_id AND r.id = e.run_id
                WHERE r.tenant_id = :tenant AND r.source_id = :source AND r.id IS DISTINCT FROM :except
                  AND e.resolved_at IS NULL AND e.file_id IS NOT NULL
                  AND (e.error_key LIKE 'FILE:%' OR e.error_key LIKE 'FOLDER:%' OR e.error_key LIKE 'PAGE:%')
                ORDER BY e.file_id LIMIT :limit
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("except", except == null ? null : except.value()).param("limit", limit)
                .query(String.class).list();
    }

    /** Resolves the Source's unresolved acquisition errors for an item this run acquired or found unchanged. */
    public void resolve(Work work, String file) {
        jdbc.sql("""
                UPDATE source_run_errors e SET resolved_at = CURRENT_TIMESTAMP, resolved_by_run_id = :id
                FROM source_sync_attempts r
                WHERE e.tenant_id = :tenant AND e.file_id = :file AND e.resolved_at IS NULL
                  AND (e.error_key LIKE 'FILE:%' OR e.error_key LIKE 'FOLDER:%' OR e.error_key LIKE 'PAGE:%')
                  AND r.tenant_id = e.tenant_id AND r.id = e.run_id AND r.source_id = :source
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("id", work.operationId().value()).param("file", file).update();
    }

    private SourceOperationView operation(ResultSet r, int ignored) throws SQLException {
        return new SourceOperationView(new SourceOperationId(r.getObject("id", UUID.class)), SourceOperationType.SYNC_SOURCE,
                JdbcSourceRepository.operationStatus(r.getString("status")), r.getTimestamp("created_at").toInstant(),
                JdbcSourceRepository.instant(r, "completed_at"), r.getString("error_code"));
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    public enum FileOutcome { ACQUIRED, UNCHANGED, SKIPPED }

    /**
     * One item the run could not acquire. {@code key} identifies it within the run ({@code FILE:}, {@code FOLDER:}
     * or {@code PAGE:} and the provider identifier); a {@code skipped} item is unsupported rather than failed.
     */
    public record ItemFailure(String key, String fileId, @Nullable String fileName, String code,
                              @Nullable String message, @Nullable String detail, boolean skipped) {}

    public record DueSource(TenantId tenantId, SourceId sourceId) {}
}
