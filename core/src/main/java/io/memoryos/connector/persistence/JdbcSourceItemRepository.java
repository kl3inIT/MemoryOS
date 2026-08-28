package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceItemId;
import io.memoryos.organization.OrganizationId;

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
            OrganizationId organizationId,
            JdbcSourceRepository.SourcePair pair,
            String filename,
            byte[] content,
            String sha256
    ) {
        var existing = jdbcClient.sql("""
                        SELECT item.id AS item_id, version.id AS version_id, item.status
                        FROM connector_items item
                        JOIN connector_item_versions version
                          ON version.organization_id = item.organization_id
                         AND version.id = item.current_version_id
                        WHERE item.organization_id = :organizationId
                          AND item.connector_id = :connectorId
                          AND item.content_sha256 = :sha256
                        """)
                .param("organizationId", organizationId.value())
                .param("connectorId", pair.connectorId())
                .param("sha256", sha256)
                .query((resultSet, ignored) -> {
                    if ("DELETING".equals(resultSet.getString("status"))) {
                        throw SourceException.conflict("source item is deleting");
                    }
                    return new ItemVersion(
                            new SourceItemId(resultSet.getObject("item_id", UUID.class)),
                            resultSet.getObject("version_id", UUID.class)
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
                            id, organization_id, connector_id, content_sha256, status
                        ) VALUES (
                            :id, :organizationId, :connectorId, :sha256, 'PENDING'
                        )
                        """)
                .param("id", itemId.value())
                .param("organizationId", organizationId.value())
                .param("connectorId", pair.connectorId())
                .param("sha256", sha256)
                .update();
        jdbcClient.sql("""
                        INSERT INTO connector_item_versions (
                            id, organization_id, connector_id, connector_item_id,
                            revision_number, filename, content_bytes, content_sha256, size_bytes
                        ) VALUES (
                            :id, :organizationId, :connectorId, :itemId,
                            1, :filename, :content, :sha256, :sizeBytes
                        )
                        """)
                .param("id", versionId)
                .param("organizationId", organizationId.value())
                .param("connectorId", pair.connectorId())
                .param("itemId", itemId.value())
                .param("filename", filename)
                .param("content", content)
                .param("sha256", sha256)
                .param("sizeBytes", content.length)
                .update();
        jdbcClient.sql("""
                        UPDATE connector_items SET current_version_id = :versionId
                        WHERE organization_id = :organizationId AND id = :itemId
                        """)
                .param("versionId", versionId)
                .param("organizationId", organizationId.value())
                .param("itemId", itemId.value())
                .update();
        return new ItemVersion(itemId, versionId);
    }

    public ItemVersion currentVersion(
            OrganizationId organizationId,
            JdbcSourceRepository.SourcePair pair,
            SourceItemId itemId
    ) {
        return jdbcClient.sql("""
                        SELECT id, current_version_id, status
                        FROM connector_items
                        WHERE organization_id = :organizationId
                          AND connector_id = :connectorId
                          AND id = :itemId
                        FOR UPDATE
                        """)
                .param("organizationId", organizationId.value())
                .param("connectorId", pair.connectorId())
                .param("itemId", itemId.value())
                .query((resultSet, ignored) -> {
                    if ("DELETING".equals(resultSet.getString("status"))) {
                        throw SourceException.conflict("source item is deleting");
                    }
                    return new ItemVersion(
                            new SourceItemId(resultSet.getObject("id", UUID.class)),
                            resultSet.getObject("current_version_id", UUID.class)
                    );
                })
                .optional()
                .orElseThrow(SourceException::notFound);
    }

    public void markDeleting(
            OrganizationId organizationId,
            JdbcSourceRepository.SourcePair pair,
            SourceItemId itemId
    ) {
        int updated = jdbcClient.sql("""
                        UPDATE connector_items SET status = 'DELETING', updated_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId
                          AND connector_id = :connectorId
                          AND id = :itemId
                        """)
                .param("organizationId", organizationId.value())
                .param("connectorId", pair.connectorId())
                .param("itemId", itemId.value())
                .update();
        if (updated != 1) {
            throw SourceException.notFound();
        }
    }

    public record ItemVersion(SourceItemId itemId, UUID versionId) {
    }
}
