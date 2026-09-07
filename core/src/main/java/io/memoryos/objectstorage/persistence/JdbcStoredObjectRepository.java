package io.memoryos.objectstorage.persistence;

import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.iam.TenantId;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcStoredObjectRepository {
    private final JdbcClient jdbcClient;

    public JdbcStoredObjectRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public void create(
            TenantId tenantId,
            StoredObjectId id,
            ObjectKey key,
            ObjectUploadSpecification specification,
            Instant expiresAt
    ) {
        jdbcClient.sql("""
                        INSERT INTO stored_objects (
                            id, tenant_id, object_key, filename, declared_media_type,
                            size_bytes, content_sha256, state, expires_at
                        ) VALUES (
                            :id, :tenantId, :objectKey, :filename, :mediaType,
                            :sizeBytes, :sha256, 'STAGED', :expiresAt
                        )
                        """)
                .param("id", id.value())
                .param("tenantId", tenantId.value())
                .param("objectKey", key.value())
                .param("filename", specification.filename())
                .param("mediaType", specification.mediaType())
                .param("sizeBytes", specification.sizeBytes())
                .param("sha256", specification.checksum().value())
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    public Optional<StoredObjectReference> find(TenantId tenantId, StoredObjectId id) {
        return jdbcClient.sql("""
                        SELECT id, object_key, filename, size_bytes, declared_media_type, content_sha256
                        FROM stored_objects
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .query((resultSet, ignored) -> new StoredObjectReference(
                        new StoredObjectId(resultSet.getObject("id", java.util.UUID.class)),
                        new ObjectKey(resultSet.getString("object_key")),
                        resultSet.getString("filename"),
                        new ObjectMetadata(
                                resultSet.getLong("size_bytes"),
                                resultSet.getString("declared_media_type"),
                                new ContentSha256(resultSet.getString("content_sha256"))
                        )
                ))
                .optional();
    }

    public boolean activate(TenantId tenantId, StoredObjectId id) {
        return jdbcClient.sql("""
                        UPDATE stored_objects
                        SET state = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :id AND state = 'STAGED'
                        """)
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .update() == 1;
    }

    public void markDeletePending(TenantId tenantId, StoredObjectId id) {
        int updated = jdbcClient.sql("""
                        UPDATE stored_objects
                        SET state = 'DELETE_PENDING', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :id AND state IN ('STAGED', 'ACTIVE', 'DELETE_PENDING')
                        """)
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Stored object could not enter delete-pending state");
        }
    }

    public void remove(TenantId tenantId, StoredObjectId id) {
        int deleted = jdbcClient.sql("""
                        DELETE FROM stored_objects
                        WHERE tenant_id = :tenantId AND id = :id AND state IN ('STAGED', 'DELETE_PENDING')
                        """)
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .update();
        if (deleted != 1) {
            throw new IllegalStateException("Stored object is not removable");
        }
    }
}
