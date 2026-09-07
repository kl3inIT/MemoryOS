package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceItemId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.tenant.TenantId;

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
                          AND item.provider_file_id IS NULL
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

    public java.util.Optional<ItemVersion> unchanged(
            io.memoryos.connector.ConnectorSyncPort.Work work, String fileId, String providerVersion) {
        return jdbcClient.sql("""
                SELECT i.id, v.id AS version_id FROM connector_items i
                JOIN connector_credential_pairs p ON p.tenant_id = i.tenant_id AND p.connector_id = i.connector_id
                JOIN connector_item_versions v ON v.tenant_id = i.tenant_id AND v.id = i.current_version_id
                WHERE p.tenant_id = :tenant AND p.id = :source AND i.provider_file_id = :file
                  AND i.status <> 'DELETING' AND v.provider_version = :version
                  AND v.scope_revision = :scope AND v.credential_revision = :credential
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("file", fileId).param("version", providerVersion).param("scope", work.scopeRevision())
                .param("credential", work.credentialRevision())
                .query((r, _) -> new ItemVersion(new SourceItemId(r.getObject("id", UUID.class)),
                        r.getObject("version_id", UUID.class), false)).optional();
    }

    public ItemVersion acceptRemote(io.memoryos.connector.ConnectorSyncPort.Work work,
            JdbcSourceRepository.SourcePair pair, StoredObjectReference object,
            io.memoryos.connector.SourceInputDescriptor input) {
        var existing = jdbcClient.sql("""
                SELECT id, status FROM connector_items
                WHERE tenant_id = :tenant AND connector_id = :connector AND provider_file_id = :file FOR UPDATE
                """).param("tenant", work.tenantId().value()).param("connector", pair.connectorId())
                .param("file", input.providerFileId()).query((r, _) -> {
                    if ("DELETING".equals(r.getString("status"))) throw SourceException.conflict("remote item is deleting");
                    return new SourceItemId(r.getObject("id", UUID.class));
                }).optional();
        SourceItemId item = existing.orElseGet(() -> new SourceItemId(UUID.randomUUID()));
        UUID version = UUID.randomUUID();
        if (existing.isEmpty()) {
            jdbcClient.sql("""
                    INSERT INTO connector_items (id, tenant_id, connector_id, provider_file_id, content_sha256, status)
                    VALUES (:id, :tenant, :connector, :file, :sha, 'PENDING')
                    """).param("id", item.value()).param("tenant", work.tenantId().value())
                    .param("connector", pair.connectorId()).param("file", input.providerFileId())
                    .param("sha", object.metadata().checksum().value()).update();
        }
        jdbcClient.sql("""
                INSERT INTO connector_item_versions (id, tenant_id, connector_id, connector_item_id,
                  revision_number, filename, stored_object_id, content_sha256, size_bytes,
                  input_format, provider_file_id, provider_version, source_url, scope_revision, credential_revision)
                SELECT :id, :tenant, :connector, :item, COALESCE(MAX(revision_number), 0) + 1,
                  :filename, :object, :sha, :size, :format, :file, :providerVersion, :url, :scope, :credential
                FROM connector_item_versions WHERE tenant_id = :tenant AND connector_item_id = :item
                """).param("id", version).param("tenant", work.tenantId().value()).param("connector", pair.connectorId())
                .param("item", item.value()).param("filename", object.filename()).param("object", object.id().value())
                .param("sha", object.metadata().checksum().value()).param("size", object.metadata().sizeBytes())
                .param("format", input.format().name()).param("file", input.providerFileId())
                .param("providerVersion", input.providerVersion()).param("url", input.sourceUrl())
                .param("scope", work.scopeRevision()).param("credential", work.credentialRevision()).update();
        jdbcClient.sql("""
                UPDATE connector_items SET current_version_id = :version, content_sha256 = :sha,
                  status = 'PENDING', updated_at = CURRENT_TIMESTAMP WHERE tenant_id = :tenant AND id = :item
                """).param("version", version).param("sha", object.metadata().checksum().value())
                .param("tenant", work.tenantId().value()).param("item", item.value()).update();
        jdbcClient.sql("""
                UPDATE google_drive_membership SET eligible = FALSE
                WHERE tenant_id = :tenant AND source_id = :source AND file_id = :file
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("file", input.providerFileId()).update();
        return new ItemVersion(item, version, true);
    }

    public java.util.List<io.memoryos.connector.CleanupObject> objects(
            io.memoryos.tenant.TenantId tenant, io.memoryos.connector.SourceId source,
            @org.jspecify.annotations.Nullable SourceItemId item) {
        return jdbcClient.sql("""
                SELECT o.* FROM connector_item_versions v
                JOIN connector_credential_pairs p ON p.tenant_id = v.tenant_id AND p.connector_id = v.connector_id
                JOIN stored_objects o ON o.tenant_id = v.tenant_id AND o.id = v.stored_object_id
                WHERE p.tenant_id = :tenant AND p.id = :source AND (:allItems OR v.connector_item_id = :item)
                ORDER BY o.id
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("allItems", item == null).param("item", item == null ? null : item.value())
                .query((r, _) -> new io.memoryos.connector.CleanupObject(new StoredObjectReference(
                        new io.memoryos.objectstorage.StoredObjectId(r.getObject("id", UUID.class)),
                        new io.memoryos.objectstorage.ObjectKey(r.getString("object_key")), r.getString("filename"),
                        new io.memoryos.objectstorage.ObjectMetadata(r.getLong("size_bytes"),
                                r.getString("declared_media_type"),
                                new io.memoryos.objectstorage.ContentSha256(r.getString("content_sha256")))))).list();
    }

    public record ItemVersion(SourceItemId itemId, UUID versionId, boolean created) {
    }
}
