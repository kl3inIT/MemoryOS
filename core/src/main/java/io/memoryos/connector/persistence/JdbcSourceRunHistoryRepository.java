package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceRun;
import io.memoryos.connector.SourceRunCounts;
import io.memoryos.connector.SourceRunError;
import io.memoryos.connector.SourceRunErrorStage;
import io.memoryos.connector.SourceRunHistoryService;
import io.memoryos.connector.SourceRunIndexingStatus;
import io.memoryos.connector.SourceRunStatus;
import io.memoryos.connector.SourceRunTrigger;
import io.memoryos.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSourceRunHistoryRepository {
    private static final String PROJECTION = """
            SELECT phase_state.*,
                CASE WHEN status IN ('NOT_STARTED', 'IN_PROGRESS') THEN acquisition_state
                    WHEN history_version IS NULL AND status = 'SUCCEEDED' THEN 'UNKNOWN'
                    WHEN indexing_pending > 0 THEN 'INDEXING'
                    WHEN status IN ('CANCELLED','SUPERSEDED') THEN status
                    WHEN status = 'FAILED' THEN CASE WHEN acquired > 0 OR unchanged > 0 THEN 'COMPLETED_WITH_ERRORS' ELSE 'FAILED' END
                    WHEN indexing_failed + indexing_cancelled + indexing_superseded > 0 THEN 'COMPLETED_WITH_ERRORS'
                    ELSE status END AS run_state,
                CASE WHEN indexing_pending IS NULL THEN 'UNKNOWN'
                    WHEN indexing_pending = 0 AND published + indexing_failed + indexing_cancelled + indexing_superseded = 0 THEN 'NOT_REQUIRED'
                    WHEN indexing_pending = 0 AND indexing_failed + indexing_cancelled + indexing_superseded > 0 THEN 'COMPLETED_WITH_ERRORS'
                    WHEN indexing_pending = 0 THEN 'SUCCEEDED'
                    WHEN EXISTS (SELECT 1 FROM index_attempts i WHERE i.tenant_id = phase_state.tenant_id
                        AND i.source_sync_attempt_id = phase_state.id AND i.status = 'IN_PROGRESS'
                        AND i.lease_expires_at <= CURRENT_TIMESTAMP) THEN 'RECOVERY_PENDING'
                    WHEN EXISTS (SELECT 1 FROM index_attempts i WHERE i.tenant_id = phase_state.tenant_id
                        AND i.source_sync_attempt_id = phase_state.id AND i.status = 'NOT_STARTED'
                        AND i.error_code IS NOT NULL) THEN 'RETRY_SCHEDULED'
                    ELSE 'PENDING' END AS indexing_state,
                CASE WHEN indexing_pending > 0 THEN (
                    SELECT MIN(i.next_dispatch_at) FROM index_attempts i WHERE i.tenant_id = phase_state.tenant_id
                        AND i.source_sync_attempt_id = phase_state.id AND i.status = 'NOT_STARTED'
                        AND i.error_code IS NOT NULL) END AS next_index_retry_at,
                COALESCE(error_code, CASE WHEN indexing_pending > 0 THEN (
                    SELECT i.error_code FROM index_attempts i WHERE i.tenant_id = phase_state.tenant_id
                        AND i.source_sync_attempt_id = phase_state.id AND i.status = 'NOT_STARTED'
                        AND i.error_code IS NOT NULL ORDER BY i.next_dispatch_at, i.id LIMIT 1) END) AS current_error_code
            FROM (
                SELECT a.*, CASE
                    WHEN status = 'IN_PROGRESS' AND lease_expires_at <= CURRENT_TIMESTAMP THEN 'RECOVERY_PENDING'
                    WHEN status = 'IN_PROGRESS' THEN 'ACQUIRING'
                    WHEN status = 'NOT_STARTED' AND error_code IS NOT NULL THEN 'RETRY_SCHEDULED'
                    WHEN status = 'NOT_STARTED' THEN 'QUEUED'
                    ELSE status END AS acquisition_state
                FROM source_sync_attempts a WHERE tenant_id = :tenant AND source_id = :source
            ) phase_state
            """;
    private final JdbcClient jdbc;

    public JdbcSourceRunHistoryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void requireSource(TenantId tenant, SourceId source) {
        if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM connector_credential_pairs WHERE tenant_id = :tenant AND id = :source)")
                .param("tenant", tenant.value()).param("source", source.value()).query(Boolean.class).single())
            throw SourceException.notFound();
    }

    public SourceRunHistoryService.Page list(TenantId tenant, SourceId source, SourceRunHistoryService.Query query) {
        String scope = scope(tenant, source, "RUN", query.status(), query.trigger(), query.from(), query.to());
        Cursor cursor = decode(query.cursor(), scope);
        String sql = "SELECT * FROM (" + PROJECTION + ") runs WHERE TRUE"
                + (query.status() == null ? "" : " AND run_state = :status")
                + (query.trigger() == null ? "" : " AND trigger_kind = :trigger")
                + (query.from() == null ? "" : " AND created_at >= :from")
                + (query.to() == null ? "" : " AND created_at < :to")
                + (cursor == null ? "" : " AND (created_at, id) < (:cursorTime, :cursorId)")
                + " ORDER BY created_at DESC, id DESC LIMIT :limit";
        var statement = jdbc.sql(sql).param("tenant", tenant.value()).param("source", source.value()).param("limit", query.size() + 1);
        if (query.status() != null) statement.param("status", query.status().name());
        if (query.trigger() != null) statement.param("trigger", query.trigger().name());
        if (query.from() != null) statement.param("from", WorkLeases.sqlTime(query.from()));
        if (query.to() != null) statement.param("to", WorkLeases.sqlTime(query.to()));
        if (cursor != null) statement.param("cursorTime", WorkLeases.sqlTime(cursor.time())).param("cursorId", cursor.id());
        List<SourceRun> found = statement.query(this::run).list();
        boolean more = found.size() > query.size();
        var items = more ? List.copyOf(found.subList(0, query.size())) : found;
        var last = items.isEmpty() ? null : items.getLast();
        return new SourceRunHistoryService.Page(items,
                more && last != null ? encode(scope, last.createdAt(), last.id()) : null,
                latest(tenant, source, "(history_version = 1 AND run_completed_at IS NULL) OR status IN ('NOT_STARTED','IN_PROGRESS')", "created_at"),
                latest(tenant, source, "run_completed_at IS NOT NULL", "run_completed_at"),
                latest(tenant, source, "run_completed_at IS NOT NULL AND status = 'SUCCEEDED' AND indexing_failed = 0 AND indexing_cancelled = 0 AND indexing_superseded = 0", "run_completed_at"));
    }

    public SourceRun get(TenantId tenant, SourceId source, UUID runId) {
        return jdbc.sql("SELECT * FROM (" + PROJECTION + ") runs WHERE id = :id")
                .param("tenant", tenant.value()).param("source", source.value()).param("id", runId)
                .query(this::run).optional().orElseThrow(SourceException::notFound);
    }

    public SourceRunHistoryService.ErrorPage errors(TenantId tenant, SourceId source, UUID runId, @Nullable String token, int size) {
        String scope = scope(tenant, source, "ERROR:" + runId, null, null, null, null);
        Cursor cursor = decode(token, scope);
        var statement = jdbc.sql("""
                SELECT e.* FROM source_run_errors e
                JOIN source_sync_attempts r ON r.tenant_id = e.tenant_id AND r.id = e.run_id
                WHERE e.tenant_id = :tenant AND r.source_id = :source AND e.run_id = :run
                """ + (cursor == null ? "" : " AND (e.occurred_at, e.id) < (:cursorTime, :cursorId)")
                + " ORDER BY e.occurred_at DESC, e.id DESC LIMIT :limit")
                .param("tenant", tenant.value()).param("source", source.value()).param("run", runId).param("limit", size + 1);
        if (cursor != null) statement.param("cursorTime", WorkLeases.sqlTime(cursor.time())).param("cursorId", cursor.id());
        var found = statement.query((r, _) -> new SourceRunError(r.getObject("id", UUID.class), runId,
                r.getObject("operation_id", UUID.class), r.getObject("item_id", UUID.class),
                r.getString("file_id"), r.getString("file_name"), SourceRunErrorStage.valueOf(r.getString("stage")),
                r.getString("code"), r.getTimestamp("occurred_at").toInstant())).list();
        boolean more = found.size() > size;
        var items = more ? List.copyOf(found.subList(0, size)) : found;
        var last = items.isEmpty() ? null : items.getLast();
        return new SourceRunHistoryService.ErrorPage(items,
                more && last != null ? encode(scope, last.occurredAt(), last.id()) : null);
    }

    private @Nullable SourceRun latest(TenantId tenant, SourceId source, String predicate, String order) {
        return jdbc.sql("SELECT * FROM (" + PROJECTION + ") runs WHERE (" + predicate + ") ORDER BY " + order + " DESC, id DESC LIMIT 1")
                .param("tenant", tenant.value()).param("source", source.value()).query(this::run).optional().orElse(null);
    }

    private SourceRun run(ResultSet r, int ignored) throws SQLException {
        String trigger = r.getString("trigger_kind");
        String acquisition = r.getString("acquisition_state");
        return new SourceRun(r.getObject("id", UUID.class), new SourceId(r.getObject("source_id", UUID.class)),
                trigger == null ? null : SourceRunTrigger.valueOf(trigger), r.getObject("actor_id", UUID.class),
                SourceRunStatus.valueOf(r.getString("run_state")), SourceRunStatus.valueOf(acquisition),
                SourceRunIndexingStatus.valueOf(r.getString("indexing_state")), r.getTimestamp("created_at").toInstant(),
                JdbcSourceRepository.instant(r, "started_at"), JdbcSourceRepository.instant(r, "completed_at"),
                JdbcSourceRepository.instant(r, "run_completed_at"), r.getLong("scope_revision"), r.getLong("credential_revision"),
                "RETRY_SCHEDULED".equals(acquisition) ? JdbcSourceRepository.instant(r, "next_dispatch_at")
                        : JdbcSourceRepository.instant(r, "next_index_retry_at"),
                r.getString("current_error_code"), r.getTimestamp("details_expired_at") != null,
                new SourceRunCounts(r.getObject("scanned", Long.class), r.getObject("acquired", Long.class),
                        r.getObject("published", Long.class), r.getObject("unchanged", Long.class),
                        r.getObject("already_pending", Long.class), r.getObject("acquisition_failed", Long.class),
                        r.getObject("indexing_failed", Long.class), r.getObject("skipped", Long.class),
                        r.getObject("removed", Long.class), r.getObject("indexing_pending", Long.class),
                        r.getObject("indexing_superseded", Long.class), r.getObject("indexing_cancelled", Long.class)));
    }

    private static String scope(TenantId tenant, SourceId source, String kind, @Nullable SourceRunStatus status,
            @Nullable SourceRunTrigger trigger, @Nullable Instant from, @Nullable Instant to) {
        return tenant.value() + "|" + source.value() + "|" + kind + "|" + status + "|" + trigger + "|" + from + "|" + to + "|";
    }

    private static String encode(String scope, Instant time, UUID id) {
        return SourceHistoryCursor.encode(scope, time + "|" + id);
    }

    private static @Nullable Cursor decode(@Nullable String token, String scope) {
        String position = SourceHistoryCursor.decode(token, scope);
        if (position == null) return null;
        try {
            String[] fields = position.split("\\|", -1);
            if (fields.length != 2) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(fields[0]), UUID.fromString(fields[1]));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw SourceHistoryCursor.invalid();
        }
    }

    private record Cursor(Instant time, UUID id) {}
}
