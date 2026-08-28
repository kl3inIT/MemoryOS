package io.memoryos.connector.persistence;

import io.memoryos.connector.IndexWork;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.document.DocumentId;
import io.memoryos.organization.OrganizationId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceDocumentRepository {

    private final JdbcClient jdbcClient;


    public boolean hasEligibleMapping(OrganizationId organizationId, DocumentId documentId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM documents_by_connector_credential_pair mapping
                        JOIN connector_credential_pairs pair
                          ON pair.organization_id = mapping.organization_id
                         AND pair.id = mapping.connector_credential_pair_id
                        JOIN connectors connector
                          ON connector.organization_id = mapping.organization_id
                         AND connector.id = mapping.connector_id
                        JOIN documents document
                          ON document.organization_id = mapping.organization_id
                         AND document.id = mapping.document_id
                        WHERE mapping.organization_id = :organizationId
                          AND mapping.document_id = :documentId
                          AND mapping.retrieval_eligible = TRUE
                          AND pair.access_type = 'PUBLIC'
                          AND pair.status = 'ACTIVE'
                          AND connector.status = 'ACTIVE'
                          AND document.status = 'ELIGIBLE'
                        """)
                .param("organizationId", organizationId.value())
                .param("documentId", documentId.value())
                .query(Integer.class)
                .single() != 0;
    }

    public JdbcSourceDocumentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public Optional<DocumentId> findMappedDocument(IndexWork work) {
        return jdbcClient.sql("""
                        SELECT document_id FROM documents_by_connector_credential_pair
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                        """)
                .param("organizationId", work.organizationId().value())
                .param("pairId", work.sourceId().value())
                .param("itemId", work.itemId().value())
                .query(UUID.class)
                .optional()
                .map(DocumentId::new);
    }

    public void publishMapping(IndexWork work, DocumentId documentId) {
        int existing = jdbcClient.sql("""
                        SELECT COUNT(*) FROM documents_by_connector_credential_pair
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                        """)
                .param("organizationId", work.organizationId().value())
                .param("pairId", work.sourceId().value())
                .param("itemId", work.itemId().value())
                .query(Integer.class)
                .single();
        if (existing == 0) {
            jdbcClient.sql("""
                            INSERT INTO documents_by_connector_credential_pair (
                                organization_id, connector_id, connector_credential_pair_id,
                                document_id, connector_item_id, retrieval_eligible
                            ) VALUES (
                                :organizationId, :connectorId, :pairId,
                                :documentId, :itemId, TRUE
                            )
                            """)
                    .param("organizationId", work.organizationId().value())
                    .param("connectorId", work.connectorId())
                    .param("pairId", work.sourceId().value())
                    .param("documentId", documentId.value())
                    .param("itemId", work.itemId().value())
                    .update();
            return;
        }
        jdbcClient.sql("""
                        UPDATE documents_by_connector_credential_pair
                        SET document_id = :documentId, retrieval_eligible = TRUE,
                            last_indexed_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                        """)
                .param("documentId", documentId.value())
                .param("organizationId", work.organizationId().value())
                .param("pairId", work.sourceId().value())
                .param("itemId", work.itemId().value())
                .update();
    }

    public void invalidateItem(OrganizationId organizationId, SourceId sourceId, SourceItemId itemId) {
        jdbcClient.sql("""
                        UPDATE documents_by_connector_credential_pair
                        SET retrieval_eligible = FALSE, last_indexed_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .param("itemId", itemId.value())
                .update();
    }

    public void invalidateSource(OrganizationId organizationId, SourceId sourceId) {
        jdbcClient.sql("""
                        UPDATE documents_by_connector_credential_pair
                        SET retrieval_eligible = FALSE, last_indexed_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .update();
    }

    public List<UUID> removeItemMappings(
            OrganizationId organizationId,
            SourceId sourceId,
            SourceItemId itemId
    ) {
        List<UUID> documents = documentIds(organizationId, sourceId, itemId);
        jdbcClient.sql("""
                        DELETE FROM documents_by_connector_credential_pair
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .param("itemId", itemId.value())
                .update();
        return documents;
    }

    public List<UUID> removeSourceMappings(OrganizationId organizationId, SourceId sourceId) {
        List<UUID> documents = documentIds(organizationId, sourceId, null);
        jdbcClient.sql("""
                        DELETE FROM documents_by_connector_credential_pair
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .update();
        return documents;
    }

    private List<UUID> documentIds(
            OrganizationId organizationId,
            SourceId sourceId,
            SourceItemId itemId
    ) {
        var statement = jdbcClient.sql(itemId == null ? """
                        SELECT document_id FROM documents_by_connector_credential_pair
                        WHERE organization_id = :organizationId AND connector_credential_pair_id = :pairId
                        """ : """
                        SELECT document_id FROM documents_by_connector_credential_pair
                        WHERE organization_id = :organizationId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value());
        if (itemId != null) {
            statement = statement.param("itemId", itemId.value());
        }
        return statement.query(UUID.class).list();
    }
}
