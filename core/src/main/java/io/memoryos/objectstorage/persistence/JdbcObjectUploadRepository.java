package io.memoryos.objectstorage.persistence;

import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.ObjectVerificationToken;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.tenant.TenantId;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcObjectUploadRepository {
    private final JdbcClient jdbcClient;

    public JdbcObjectUploadRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public void create(TenantId tenantId, ObjectUploadId id, StoredObjectId storedObjectId) {
        jdbcClient.sql("""
                        INSERT INTO object_uploads (id, tenant_id, stored_object_id, status)
                        VALUES (:id, :tenantId, :storedObjectId, 'PENDING')
                        """)
                .param("id", id.value())
                .param("tenantId", tenantId.value())
                .param("storedObjectId", storedObjectId.value())
                .update();
    }

    public Optional<UploadRow> find(TenantId tenantId, ObjectUploadId id) {
        return jdbcClient.sql("""
                        SELECT id, stored_object_id, status, verification_token,
                               verification_lease_until, adoption_deadline
                        FROM object_uploads
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .query((resultSet, ignored) -> new UploadRow(
                        new ObjectUploadId(resultSet.getObject("id", UUID.class)),
                        optionalStoredObjectId(resultSet),
                        resultSet.getString("status"),
                        resultSet.getObject("verification_token", UUID.class),
                        optionalInstant(resultSet, "verification_lease_until"),
                        optionalInstant(resultSet, "adoption_deadline")
                ))
                .optional();
    }

    public boolean claimVerification(
            TenantId tenantId,
            ObjectUploadId id,
            ObjectVerificationToken token,
            Instant now,
            Instant leaseUntil
    ) {
        return jdbcClient.sql("""
                        UPDATE object_uploads
                        SET status = 'VERIFYING', verification_token = :token,
                            verification_lease_until = :leaseUntil, error_code = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :id
                          AND EXISTS (
                            SELECT 1 FROM stored_objects object
                            WHERE object.tenant_id = object_uploads.tenant_id
                              AND object.id = object_uploads.stored_object_id
                              AND object.expires_at >= :now
                          )
                          AND (status = 'PENDING' OR (status = 'VERIFYING' AND verification_lease_until < :now))
                        """)
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .param("token", token.value())
                .param("leaseUntil", Timestamp.from(leaseUntil))
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    public boolean completeVerification(
            TenantId tenantId,
            ObjectUploadId id,
            ObjectVerificationToken token,
            Instant verifiedAt,
            Instant adoptionDeadline
    ) {
        return jdbcClient.sql("""
                        UPDATE object_uploads
                        SET status = 'VERIFIED', verification_lease_until = NULL,
                            verified_at = :verifiedAt, adoption_deadline = :adoptionDeadline,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :id
                          AND status = 'VERIFYING' AND verification_token = :token
                        """)
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .param("token", token.value())
                .param("verifiedAt", Timestamp.from(verifiedAt))
                .param("adoptionDeadline", Timestamp.from(adoptionDeadline))
                .update() == 1;
    }

    public void releaseVerification(TenantId tenantId, ObjectUploadId id, ObjectVerificationToken token, String errorCode) {
        jdbcClient.sql("""
                        UPDATE object_uploads
                        SET status = 'PENDING', verification_token = NULL,
                            verification_lease_until = NULL, error_code = :errorCode,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :id
                          AND status = 'VERIFYING' AND verification_token = :token
                        """)
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .param("token", token.value())
                .param("errorCode", errorCode)
                .update();
    }

    public boolean adopt(TenantId tenantId, ObjectUploadId id, ObjectVerificationToken token, Instant now) {
        return transitionVerified(tenantId, id, token, now, "ADOPTED");
    }

    public boolean discard(TenantId tenantId, ObjectUploadId id, ObjectVerificationToken token, Instant now) {
        return transitionVerified(tenantId, id, token, now, "DISCARDED");
    }

    private boolean transitionVerified(
            TenantId tenantId, ObjectUploadId id, ObjectVerificationToken token, Instant now, String status
    ) {
        return jdbcClient.sql("""
                        UPDATE object_uploads
                        SET status = :status, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :id
                          AND status = 'VERIFIED' AND verification_token = :token
                          AND adoption_deadline >= :now
                        """)
                .param("status", status)
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .param("token", token.value())
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    public void releaseAdopted(TenantId tenantId, ObjectUploadId id) {
        int deleted = jdbcClient.sql("""
                        DELETE FROM object_uploads
                        WHERE tenant_id = :tenantId AND id = :id AND status = 'ADOPTED'
                        """)
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .update();
        if (deleted != 1) {
            throw new IllegalStateException("Adopted object upload was not released");
        }
    }

    public List<CleanupRow> claimAbandoned(Instant now, Instant leaseUntil, UUID token, int limit) {
        List<CleanupRow> rows = jdbcClient.sql("""
                        SELECT upload.id, upload.tenant_id, upload.stored_object_id
                        FROM object_uploads upload
                        JOIN stored_objects object ON object.tenant_id = upload.tenant_id
                                                  AND object.id = upload.stored_object_id
                        WHERE object.state IN ('STAGED', 'DELETE_PENDING')
                          AND (
                            (upload.status = 'PENDING' AND object.expires_at < :now)
                            OR (upload.status = 'VERIFYING' AND upload.verification_lease_until < :now AND object.expires_at < :now)
                            OR (upload.status = 'VERIFIED' AND upload.adoption_deadline < :now)
                            OR upload.status = 'DISCARDED'
                            OR (upload.status = 'CLEANING' AND upload.cleanup_lease_until < :now)
                          )
                        ORDER BY upload.created_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((resultSet, ignored) -> new CleanupRow(
                        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                        new ObjectUploadId(resultSet.getObject("id", UUID.class)),
                        new StoredObjectId(resultSet.getObject("stored_object_id", UUID.class)),
                        token
                ))
                .list();
        for (CleanupRow row : rows) {
            jdbcClient.sql("""
                            UPDATE object_uploads
                            SET status = 'CLEANING', cleanup_token = :token,
                                cleanup_lease_until = :leaseUntil,
                                cleanup_attempts = cleanup_attempts + 1,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE tenant_id = :tenantId AND id = :id
                            """)
                    .param("token", token)
                    .param("leaseUntil", Timestamp.from(leaseUntil))
                    .param("tenantId", row.tenantId().value())
                    .param("id", row.uploadId().value())
                    .update();
        }
        return rows;
    }

    public void expireClaimed(CleanupRow row) {
        int updated = jdbcClient.sql("""
                        UPDATE object_uploads
                        SET status = 'EXPIRED', stored_object_id = NULL,
                            verification_token = NULL, verification_lease_until = NULL,
                            cleanup_token = NULL, cleanup_lease_until = NULL,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :id
                          AND status = 'CLEANING' AND cleanup_token = :token
                        """)
                .param("tenantId", row.tenantId().value())
                .param("id", row.uploadId().value())
                .param("token", row.cleanupToken())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Object upload cleanup claim was lost");
        }
    }


    private static Instant optionalInstant(
            java.sql.ResultSet resultSet,
            String column
    ) throws java.sql.SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static StoredObjectId optionalStoredObjectId(
            java.sql.ResultSet resultSet
    ) throws java.sql.SQLException {
        UUID value = resultSet.getObject("stored_object_id", UUID.class);
        return value == null ? null : new StoredObjectId(value);
    }

    public record UploadRow(
            ObjectUploadId id,
            StoredObjectId storedObjectId,
            String status,
            UUID verificationToken,
            Instant verificationLeaseUntil,
            Instant adoptionDeadline
    ) {
    }

    public record CleanupRow(
            TenantId tenantId,
            ObjectUploadId uploadId,
            StoredObjectId storedObjectId,
            UUID cleanupToken
    ) {
    }
}
