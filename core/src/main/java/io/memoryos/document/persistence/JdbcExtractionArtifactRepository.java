package io.memoryos.document.persistence;

import io.memoryos.iam.TenantId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcExtractionArtifactRepository {
    private final JdbcClient jdbc;

    public JdbcExtractionArtifactRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void stage(TenantId tenant, UUID id, String key, String hash, long size) {
        jdbc.sql("""
                INSERT INTO document_extraction_artifacts
                    (tenant_id,id,object_key,content_sha256,size_bytes,state,expires_at)
                VALUES (:tenant,:id,:key,:hash,:size,'STAGED',CURRENT_TIMESTAMP + INTERVAL '1' HOUR)
                """).param("tenant", tenant.value()).param("id", id).param("key", key)
                .param("hash", hash).param("size", size).update();
    }

    public List<CleanupArtifact> claimCleanup() {
        UUID token = UUID.randomUUID();
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT a.tenant_id,a.id FROM document_extraction_artifacts a
                    WHERE ((a.state='STAGED' AND a.expires_at<CURRENT_TIMESTAMP) OR a.state IN ('ACTIVE','DELETING'))
                      AND (a.cleanup_until IS NULL OR a.cleanup_until<CURRENT_TIMESTAMP)
                      AND NOT EXISTS (SELECT 1 FROM documents v
                          WHERE v.tenant_id=a.tenant_id AND v.extraction_artifact_id=a.id)
                    ORDER BY a.expires_at LIMIT 20 FOR UPDATE SKIP LOCKED
                )
                UPDATE document_extraction_artifacts a SET state='DELETING',cleanup_token=:token,
                    cleanup_until=CURRENT_TIMESTAMP + INTERVAL '2' MINUTE
                FROM candidates c WHERE a.tenant_id=c.tenant_id AND a.id=c.id
                RETURNING a.tenant_id,a.id,a.object_key,a.cleanup_token
                """).param("token", token).query((rs, _) -> new CleanupArtifact(
                        new TenantId(rs.getObject("tenant_id", UUID.class)), rs.getObject("id", UUID.class),
                        rs.getString("object_key"), rs.getObject("cleanup_token", UUID.class))).list();
    }

    public void finishWrite(TenantId tenant, UUID id) {
        // Set only after PUT and integrity verification have returned successfully.
        // An expired writer must not resurrect an artifact already claimed for deletion.
        jdbc.sql("""
                UPDATE document_extraction_artifacts SET write_complete=TRUE
                WHERE tenant_id=:tenant AND id=:id
                """).param("tenant", tenant.value()).param("id", id).update();
    }

    public void remove(CleanupArtifact artifact) {
        jdbc.sql("""
                DELETE FROM document_extraction_artifacts WHERE tenant_id=:tenant AND id=:id
                  AND state='DELETING' AND cleanup_token=:token AND write_complete=TRUE
                """).param("tenant", artifact.tenantId().value()).param("id", artifact.id())
                .param("token", artifact.token()).update();
        // A timed-out PUT may still finish remotely. Retain its tombstone and
        // repeat deletion instead of forgetting a potentially late object.
        jdbc.sql("""
                UPDATE document_extraction_artifacts SET cleanup_until=CURRENT_TIMESTAMP + INTERVAL '1' DAY
                WHERE tenant_id=:tenant AND id=:id AND state='DELETING'
                  AND cleanup_token=:token AND write_complete=FALSE
                """).param("tenant", artifact.tenantId().value()).param("id", artifact.id())
                .param("token", artifact.token()).update();
    }

    public record CleanupArtifact(TenantId tenantId, UUID id, String key, UUID token) { }
}
