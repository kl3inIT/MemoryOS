package io.memoryos.connector.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSourceRunRetentionRepository {
    private final JdbcClient jdbc;

    public JdbcSourceRunRetentionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public int prune(Instant summaryCutoff, Instant detailCutoff, int limit) {
        var candidates = jdbc.sql("""
                SELECT r.tenant_id, r.id FROM source_sync_attempts r
                WHERE r.status NOT IN ('NOT_STARTED', 'IN_PROGRESS')
                    AND (r.run_completed_at IS NOT NULL OR r.history_version IS NULL)
                    AND COALESCE(r.run_completed_at, r.completed_at) < :detailCutoff
                    AND (r.details_expired_at IS NULL OR (
                        COALESCE(r.run_completed_at, r.completed_at) < :summaryCutoff
                        AND NOT EXISTS (SELECT 1 FROM index_attempts protected
                            WHERE protected.tenant_id = r.tenant_id AND protected.source_sync_attempt_id = r.id
                                AND (EXISTS (SELECT 1 FROM connector_items current_item
                                    WHERE current_item.tenant_id = protected.tenant_id AND current_item.id = protected.connector_item_id
                                        AND current_item.current_version_id = protected.connector_item_version_id)
                                    OR EXISTS (SELECT 1 FROM source_uploads receipt
                                        WHERE receipt.tenant_id = protected.tenant_id AND receipt.index_attempt_id = protected.id)))))
                    AND NOT EXISTS (SELECT 1 FROM index_attempts i WHERE i.tenant_id = r.tenant_id
                        AND i.connector_credential_pair_id = r.source_id
                        AND (i.source_sync_attempt_id = r.id OR r.history_version IS NULL)
                        AND i.status IN ('NOT_STARTED','IN_PROGRESS'))
                    AND NOT EXISTS (SELECT 1 FROM index_attempts i JOIN connector_items item
                        ON item.tenant_id = i.tenant_id AND item.id = i.connector_item_id
                        WHERE i.tenant_id = r.tenant_id AND i.source_sync_attempt_id = r.id
                            AND i.status = 'FAILED' AND item.status = 'FAILED'
                            AND item.current_version_id = i.connector_item_version_id)
                    AND (r.status <> 'FAILED' OR EXISTS (
                        SELECT 1 FROM source_sync_attempts recovered WHERE recovered.tenant_id = r.tenant_id
                            AND recovered.source_id = r.source_id
                            AND recovered.run_completed_at > COALESCE(r.run_completed_at, r.completed_at)
                            AND recovered.status = 'SUCCEEDED' AND recovered.indexing_failed = 0
                            AND recovered.indexing_superseded = 0 AND recovered.indexing_cancelled = 0))
                ORDER BY COALESCE(r.run_completed_at, r.completed_at), r.id
                LIMIT :limit FOR UPDATE OF r SKIP LOCKED
                """).param("summaryCutoff", WorkLeases.sqlTime(summaryCutoff)).param("detailCutoff", WorkLeases.sqlTime(detailCutoff))
                .param("limit", limit).query((r, _) -> new Candidate(r.getObject("tenant_id", UUID.class),
                        r.getObject("id", UUID.class))).list();
        for (var candidate : candidates) {
            compact(candidate);
            deleteUnreferencedSummary(candidate, summaryCutoff);
        }
        return candidates.size();
    }

    private void compact(Candidate run) {
        jdbc.sql("DELETE FROM source_run_files WHERE tenant_id = :tenant AND run_id = :run")
                .param("tenant", run.tenant()).param("run", run.id()).update();
        jdbc.sql("DELETE FROM source_run_errors WHERE tenant_id = :tenant AND run_id = :run")
                .param("tenant", run.tenant()).param("run", run.id()).update();
        jdbc.sql("DELETE FROM google_drive_frontier WHERE tenant_id = :tenant AND attempt_id = :run")
                .param("tenant", run.tenant()).param("run", run.id()).update();
        jdbc.sql("UPDATE source_sync_attempts SET details_expired_at = COALESCE(details_expired_at, CURRENT_TIMESTAMP) WHERE tenant_id = :tenant AND id = :run")
                .param("tenant", run.tenant()).param("run", run.id()).update();
    }

    private void deleteUnreferencedSummary(Candidate run, Instant cutoff) {
        // Current input attempts and upload receipts remain recovery/status authorities, regardless of age.
        jdbc.sql("""
                DELETE FROM index_attempts i USING source_sync_attempts r
                WHERE r.tenant_id = :tenant AND r.id = :run AND i.tenant_id = r.tenant_id
                    AND i.source_sync_attempt_id = r.id AND COALESCE(r.run_completed_at, r.completed_at) < :cutoff
                    AND i.status NOT IN ('NOT_STARTED','IN_PROGRESS')
                    AND NOT EXISTS (SELECT 1 FROM connector_items item WHERE item.tenant_id = i.tenant_id
                        AND item.id = i.connector_item_id AND item.current_version_id = i.connector_item_version_id)
                    AND NOT EXISTS (SELECT 1 FROM source_uploads u WHERE u.tenant_id = i.tenant_id AND u.index_attempt_id = i.id)
                """).param("tenant", run.tenant()).param("run", run.id()).param("cutoff", WorkLeases.sqlTime(cutoff)).update();
        jdbc.sql("""
                DELETE FROM source_sync_attempts r WHERE r.tenant_id = :tenant AND r.id = :run
                    AND COALESCE(r.run_completed_at, r.completed_at) < :cutoff
                    AND NOT EXISTS (SELECT 1 FROM index_attempts i WHERE i.tenant_id = r.tenant_id AND i.source_sync_attempt_id = r.id)
                """).param("tenant", run.tenant()).param("run", run.id()).param("cutoff", WorkLeases.sqlTime(cutoff)).update();
    }

    private record Candidate(UUID tenant, UUID id) {}
}
