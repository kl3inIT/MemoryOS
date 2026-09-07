package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceItemId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.iam.TenantId;

import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceItemRepository {

    private final JdbcClient jdbcClient;

    public JdbcSourceItemRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public ItemVersion resolveOrCreate(
            TenantId tenantId,
            JdbcSourceRepository.SourcePair pair,
            String filename,
            StoredObjectReference object
    ) {
        var existing = jdbcClient.sql("""
                        SELECT item.id AS item_id, version.id AS version_id, item.status
                        FROM connector_items item
                        JOIN connector_item_versions version
                          ON version.tenant_id = item.tenant_id
                         AND version.id = item.current_version_id
                        WHERE item.tenant_id = :tenantId
                          AND item.connector_id = :connectorId
                          AND item.content_sha256 = :sha256
                        """)
                .param("tenantId", tenantId.value())
                .param("connectorId", pair.connectorId())
                .param("sha256", object.metadata().checksum().value())
                .query((resultSet, ignored) -> {
                    if ("DELETING".equals(resultSet.getString("status"))) {
                        throw SourceException.conflict("source item is deleting");
                    }
                    return new ItemVersion(
                            new SourceItemId(resultSet.getObject("item_id", UUID.class)),
                            resultSet.getObject("version_id", UUID.class),
                            false
                    );
                })
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }

        SourceItemId itemId = new SourceItemId(UUID.randomUUID());
        UUID versionId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO connector_items (
                            id, tenant_id, connector_id, content_sha256, status
                        ) VALUES (
                            :id, :tenantId, :connectorId, :sha256, 'PENDING'
                        )
                        """)
                .param("id", itemId.value())
                .param("tenantId", tenantId.value())
                .param("connectorId", pair.connectorId())
                .param("sha256", object.metadata().checksum().value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO connector_item_versions (
                            id, tenant_id, connector_id, connector_item_id,
                            revision_number, filename, stored_object_id, content_sha256, size_bytes
                        ) VALUES (
                            :id, :tenantId, :connectorId, :itemId,
                            1, :filename, :storedObjectId, :sha256, :sizeBytes
                        )
                        """)
                .param("id", versionId)
                .param("tenantId", tenantId.value())
                .param("connectorId", pair.connectorId())
                .param("itemId", itemId.value())
                .param("filename", filename)
                .param("storedObjectId", object.id().value())
                .param("sha256", object.metadata().checksum().value())
                .param("sizeBytes", object.metadata().sizeBytes())
                .update();
        jdbcClient.sql("""
                        UPDATE connector_items SET current_version_id = :versionId
                        WHERE tenant_id = :tenantId AND id = :itemId
                        """)
                .param("versionId", versionId)
                .param("tenantId", tenantId.value())
                .param("itemId", itemId.value())
                .update();
        return new ItemVersion(itemId, versionId, true);
    }

    public ItemVersion lockCurrentVersion(
            TenantId tenantId,
            JdbcSourceRepository.SourcePair pair,
            SourceItemId itemId
    ) {
        return jdbcClient.sql("""
                        SELECT id, current_version_id, status
                        FROM connector_items
                        WHERE tenant_id = :tenantId
                          AND connector_id = :connectorId
                          AND id = :itemId
                        FOR UPDATE
                        """)
                .param("tenantId", tenantId.value())
                .param("connectorId", pair.connectorId())
                .param("itemId", itemId.value())
                .query((resultSet, ignored) -> {
                    if ("DELETING".equals(resultSet.getString("status"))) {
                        throw SourceException.conflict("source item is deleting");
                    }
                    return new ItemVersion(
                            new SourceItemId(resultSet.getObject("id", UUID.class)),
                            resultSet.getObject("current_version_id", UUID.class),
                            false
                    );
                })
                .optional()
                .orElseThrow(SourceException::notFound);
    }

    public void markDeleting(
            TenantId tenantId,
            JdbcSourceRepository.SourcePair pair,
            SourceItemId itemId
    ) {
        int updated = jdbcClient.sql("""
                        UPDATE connector_items SET status = 'DELETING', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND connector_id = :connectorId
                          AND id = :itemId
                        """)
                .param("tenantId", tenantId.value())
                .param("connectorId", pair.connectorId())
                .param("itemId", itemId.value())
                .update();
        if (updated != 1) {
            throw SourceException.notFound();
        }
    }

    public record ItemVersion(SourceItemId itemId, UUID versionId, boolean created) {
    }
}
