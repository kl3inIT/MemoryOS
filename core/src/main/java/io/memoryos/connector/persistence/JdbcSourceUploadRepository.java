package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.objectstorage.ObjectUploadId;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.iam.TenantId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceUploadRepository {
    private final JdbcClient jdbcClient;

    public JdbcSourceUploadRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public void create(TenantId tenantId, SourceId sourceId, ObjectUploadId uploadId) {
        jdbcClient.sql("""
                        INSERT INTO source_uploads (tenant_id, connector_credential_pair_id, object_upload_id)
                        VALUES (:tenantId, :sourceId, :uploadId)
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .param("uploadId", uploadId.value())
                .update();
    }

    public boolean exists(TenantId tenantId, SourceId sourceId, ObjectUploadId uploadId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM source_uploads
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :sourceId
                          AND object_upload_id = :uploadId
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .param("uploadId", uploadId.value())
                .query(Integer.class)
                .single() == 1;
    }

    public Optional<ReceiptIds> findReceipt(TenantId tenantId, SourceId sourceId, ObjectUploadId uploadId) {
        return jdbcClient.sql("""
                        SELECT connector_item_id, connector_item_version_id, index_attempt_id
                        FROM source_uploads
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :sourceId
                          AND object_upload_id = :uploadId
                          AND finalized_at IS NOT NULL
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .param("uploadId", uploadId.value())
                .query((resultSet, ignored) -> new ReceiptIds(
                        new SourceItemId(resultSet.getObject("connector_item_id", UUID.class)),
                        resultSet.getObject("connector_item_version_id", UUID.class),
                        new SourceOperationId(resultSet.getObject("index_attempt_id", UUID.class))
                ))
                .optional();
    }

    public boolean complete(
            TenantId tenantId,
            SourceId sourceId,
            ObjectUploadId uploadId,
            JdbcSourceItemRepository.ItemVersion version,
            SourceOperationId operationId
    ) {
        return jdbcClient.sql("""
                        UPDATE source_uploads
                        SET connector_item_id = :itemId,
                            connector_item_version_id = :versionId,
                            index_attempt_id = :attemptId,
                            finalized_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :sourceId
                          AND object_upload_id = :uploadId
                          AND finalized_at IS NULL
                        """)
                .param("itemId", version.itemId().value())
                .param("versionId", version.versionId())
                .param("attemptId", operationId.value())
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .param("uploadId", uploadId.value())
                .update() == 1;
    }

    public List<AdoptedObject> findForItem(TenantId tenantId, SourceId sourceId, SourceItemId itemId) {
        return jdbcClient.sql("""
                        SELECT upload.object_upload_id, object.id AS stored_object_id, object.object_key,
                               object.filename, object.size_bytes, object.declared_media_type, object.content_sha256
                        FROM source_uploads upload
                        JOIN object_uploads object_upload ON object_upload.tenant_id = upload.tenant_id
                                                         AND object_upload.id = upload.object_upload_id
                        JOIN stored_objects object ON object.tenant_id = object_upload.tenant_id
                                                  AND object.id = object_upload.stored_object_id
                        WHERE upload.tenant_id = :tenantId
                          AND upload.connector_credential_pair_id = :sourceId
                          AND upload.connector_item_id = :itemId
                          AND upload.finalized_at IS NOT NULL
                          AND object_upload.status = 'ADOPTED'
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .param("itemId", itemId.value())
                .query((resultSet, ignored) -> adoptedObject(resultSet))
                .list();
    }

    public List<AdoptedObject> findForSource(TenantId tenantId, SourceId sourceId) {
        return jdbcClient.sql("""
                        SELECT upload.object_upload_id, object.id AS stored_object_id, object.object_key,
                               object.filename, object.size_bytes, object.declared_media_type, object.content_sha256
                        FROM source_uploads upload
                        JOIN object_uploads object_upload ON object_upload.tenant_id = upload.tenant_id
                                                         AND object_upload.id = upload.object_upload_id
                        JOIN stored_objects object ON object.tenant_id = object_upload.tenant_id
                                                  AND object.id = object_upload.stored_object_id
                        WHERE upload.tenant_id = :tenantId
                          AND upload.connector_credential_pair_id = :sourceId
                          AND upload.finalized_at IS NOT NULL
                          AND object_upload.status = 'ADOPTED'
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .query((resultSet, ignored) -> adoptedObject(resultSet))
                .list();
    }

    public void remove(TenantId tenantId, SourceId sourceId, ObjectUploadId uploadId) {
        jdbcClient.sql("""
                        DELETE FROM source_uploads
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :sourceId
                          AND object_upload_id = :uploadId
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .param("uploadId", uploadId.value())
                .update();
    }

    public void removeRemainingForItem(TenantId tenantId, SourceId sourceId, SourceItemId itemId) {
        jdbcClient.sql("""
                        DELETE FROM source_uploads
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :sourceId
                          AND connector_item_id = :itemId
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .param("itemId", itemId.value())
                .update();
    }

    public void removeRemainingForSource(TenantId tenantId, SourceId sourceId) {
        jdbcClient.sql("""
                        DELETE FROM source_uploads
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :sourceId
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .update();
    }

    private static AdoptedObject adoptedObject(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new AdoptedObject(
                new ObjectUploadId(resultSet.getObject("object_upload_id", UUID.class)),
                new StoredObjectReference(
                        new StoredObjectId(resultSet.getObject("stored_object_id", UUID.class)),
                        new ObjectKey(resultSet.getString("object_key")),
                        resultSet.getString("filename"),
                        new ObjectMetadata(
                                resultSet.getLong("size_bytes"),
                                resultSet.getString("declared_media_type"),
                                new ContentSha256(resultSet.getString("content_sha256"))
                        )
                )
        );
    }

    public record ReceiptIds(SourceItemId itemId, UUID versionId, SourceOperationId operationId) {
    }

    public record AdoptedObject(ObjectUploadId uploadId, StoredObjectReference object) {
    }
}
