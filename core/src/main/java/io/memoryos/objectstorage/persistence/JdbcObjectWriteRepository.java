package io.memoryos.objectstorage.persistence;

import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.iam.TenantId;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcObjectWriteRepository {
    private final JdbcClient jdbc;

    public JdbcObjectWriteRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    public void reserve(TenantId tenant, StoredObjectId id, UUID token, Instant deadline) {
        jdbc.sql("""
                INSERT INTO object_writes (tenant_id, stored_object_id, write_token, status, write_deadline)
                VALUES (:tenant, :id, :token, 'WRITING', :deadline)
                """).param("tenant", tenant.value()).param("id", id.value()).param("token", token)
                .param("deadline", Timestamp.from(deadline)).update();
    }

    public boolean finishWrite(TenantId tenant, StoredObjectId id, UUID token, Instant now, Instant adoptionDeadline) {
        // Positive completion may settle a tombstone, but must never revive a cleanup claim.
        return jdbc.sql("""
                UPDATE object_writes SET write_complete = TRUE,
                    status = CASE WHEN status = 'WRITING' AND write_deadline > :now THEN 'READY' ELSE status END,
                    adoption_deadline = :deadline, updated_at = :now
                WHERE tenant_id = :tenant AND stored_object_id = :id AND write_token = :token
                RETURNING status
                """).param("tenant", tenant.value()).param("id", id.value()).param("token", token)
                .param("now", Timestamp.from(now)).param("deadline", Timestamp.from(adoptionDeadline))
                .query(String.class).optional().filter("READY"::equals).isPresent();
    }

    public boolean adopt(TenantId tenant, StoredObjectId id, UUID token, Instant now) {
        return jdbc.sql("""
                UPDATE object_writes SET status = 'ADOPTED', updated_at = :now
                WHERE tenant_id = :tenant AND stored_object_id = :id AND write_token = :token
                    AND status = 'READY' AND write_complete = TRUE AND adoption_deadline > :now
                """).param("tenant", tenant.value()).param("id", id.value()).param("token", token)
                .param("now", Timestamp.from(now)).update() == 1;
    }

    public boolean discard(TenantId tenant, StoredObjectId id, UUID token, Instant now) {
        return jdbc.sql("""
                UPDATE object_writes SET status = 'DISCARDED', updated_at = :now
                WHERE tenant_id = :tenant AND stored_object_id = :id AND write_token = :token
                    AND status IN ('WRITING', 'READY', 'DISCARDED')
                """).param("tenant", tenant.value()).param("id", id.value()).param("token", token)
                .param("now", Timestamp.from(now)).update() == 1;
    }

    public boolean releaseAdopted(TenantId tenant, StoredObjectId id) {
        var status = jdbc.sql("""
                SELECT status FROM object_writes
                WHERE tenant_id = :tenant AND stored_object_id = :id FOR UPDATE
                """).param("tenant", tenant.value()).param("id", id.value()).query(String.class).optional();
        if (status.isEmpty()) return true;
        if (!"ADOPTED".equals(status.get())) return false;
        return jdbc.sql("""
                DELETE FROM object_writes w WHERE tenant_id = :tenant AND stored_object_id = :id
                    AND status = 'ADOPTED' AND write_complete = TRUE
                    AND NOT EXISTS (SELECT 1 FROM connector_item_versions v
                        WHERE v.tenant_id = w.tenant_id AND v.stored_object_id = w.stored_object_id)
                """).param("tenant", tenant.value()).param("id", id.value()).update() == 1;
    }

    public List<CleanupWrite> claimCleanup(Instant now, Instant leaseUntil, UUID token, int limit) {
        return jdbc.sql("""
                WITH candidates AS (
                    SELECT w.tenant_id, w.stored_object_id FROM object_writes w
                    WHERE ((w.status = 'WRITING' AND w.write_deadline <= :now)
                        OR (w.status = 'READY' AND w.adoption_deadline <= :now)
                        OR w.status = 'DISCARDED'
                        OR (w.status = 'CLEANING' AND w.cleanup_lease_until <= :now))
                        AND NOT EXISTS (SELECT 1 FROM connector_item_versions v
                            WHERE v.tenant_id = w.tenant_id AND v.stored_object_id = w.stored_object_id)
                    ORDER BY COALESCE(w.cleanup_lease_until, w.adoption_deadline, w.write_deadline), w.created_at
                    LIMIT :limit FOR UPDATE OF w SKIP LOCKED
                )
                UPDATE object_writes w SET status = 'CLEANING', cleanup_token = :token,
                    cleanup_lease_until = :leaseUntil, updated_at = :now
                FROM candidates c, stored_objects o
                WHERE w.tenant_id = c.tenant_id AND w.stored_object_id = c.stored_object_id
                    AND o.tenant_id = w.tenant_id AND o.id = w.stored_object_id
                RETURNING w.tenant_id, w.stored_object_id, o.object_key, w.cleanup_token, w.write_complete
                """).param("now", Timestamp.from(now)).param("leaseUntil", Timestamp.from(leaseUntil))
                .param("token", token).param("limit", limit).query((rs, ignored) -> new CleanupWrite(
                        new TenantId(rs.getObject("tenant_id", UUID.class)),
                        new StoredObjectId(rs.getObject("stored_object_id", UUID.class)),
                        new ObjectKey(rs.getString("object_key")), rs.getObject("cleanup_token", UUID.class),
                        rs.getBoolean("write_complete"))).list();
    }

    public boolean finishCleanup(CleanupWrite claim, Instant retryAt) {
        // Use completion captured BEFORE DELETE. A writer finishing during DELETE needs another sweep.
        if (claim.writeComplete()) {
            return jdbc.sql("""
                    DELETE FROM object_writes w
                    WHERE tenant_id = :tenant AND stored_object_id = :id
                        AND status = 'CLEANING' AND cleanup_token = :token AND write_complete = TRUE
                        AND NOT EXISTS (SELECT 1 FROM connector_item_versions v
                            WHERE v.tenant_id = w.tenant_id AND v.stored_object_id = w.stored_object_id)
                    """).param("tenant", claim.tenantId().value()).param("id", claim.objectId().value())
                    .param("token", claim.token()).update() == 1;
        }
        // A timeout is not proof the remote PUT stopped: keep its key durably and delete again.
        jdbc.sql("""
                UPDATE object_writes SET cleanup_lease_until = :retryAt, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND stored_object_id = :id
                    AND status = 'CLEANING' AND cleanup_token = :token
                """).param("tenant", claim.tenantId().value()).param("id", claim.objectId().value())
                .param("token", claim.token()).param("retryAt", Timestamp.from(retryAt)).update();
        return false;
    }

    public record CleanupWrite(TenantId tenantId, StoredObjectId objectId, ObjectKey key, UUID token,
            boolean writeComplete) {}
}
