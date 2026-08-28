package io.memoryos.document.persistence;

import io.memoryos.document.DocumentCommandService;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.DocumentId;
import io.memoryos.organization.OrganizationId;

import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import org.jspecify.annotations.Nullable;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcDocumentCommandService implements DocumentCommandService {

    private final JdbcClient jdbcClient;

    public JdbcDocumentCommandService(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    @Transactional
    public DocumentId publish(
            OrganizationId organizationId,
            @Nullable DocumentId existingDocumentId,
            DocumentContent extraction,
            String sourceSha256
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(extraction, "content must not be null");
        Objects.requireNonNull(sourceSha256, "sourceSha256 must not be null");
        DocumentId documentId = existingDocumentId == null
                ? new DocumentId(UUID.randomUUID())
                : existingDocumentId;
        if (existingDocumentId == null) {
            jdbcClient.sql("""
                            INSERT INTO documents (id, organization_id, status)
                            VALUES (:id, :organizationId, 'ELIGIBLE')
                            """)
                    .param("id", documentId.value())
                    .param("organizationId", organizationId.value())
                    .update();
        } else {
            boolean locked = jdbcClient.sql("""
                            SELECT id FROM documents
                            WHERE organization_id = :organizationId AND id = :id
                            FOR UPDATE
                            """)
                    .param("organizationId", organizationId.value())
                    .param("id", documentId.value())
                    .query(UUID.class)
                    .optional()
                    .isPresent();
            if (!locked) {
                throw new IllegalStateException("mapped document is missing");
            }
            boolean sameContent = jdbcClient.sql("""
                            SELECT COUNT(*) FROM document_versions
                            WHERE organization_id = :organizationId
                              AND document_id = :documentId
                              AND source_content_sha256 = :sha256
                            """)
                    .param("organizationId", organizationId.value())
                    .param("documentId", documentId.value())
                    .param("sha256", sourceSha256)
                    .query(Integer.class)
                    .single() != 0;
            if (sameContent) {
                jdbcClient.sql("""
                                UPDATE documents
                                SET status = 'ELIGIBLE', updated_at = CURRENT_TIMESTAMP
                                WHERE organization_id = :organizationId AND id = :documentId
                                """)
                        .param("organizationId", organizationId.value())
                        .param("documentId", documentId.value())
                        .update();
                return documentId;
            }
        }

        Long versionNumber = jdbcClient.sql("""
                        SELECT COALESCE(MAX(version_number), 0) + 1
                        FROM document_versions
                        WHERE organization_id = :organizationId AND document_id = :documentId
                        """)
                .param("organizationId", organizationId.value())
                .param("documentId", documentId.value())
                .query(Long.class)
                .single();
        UUID versionId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO document_versions (
                            id, organization_id, document_id, version_number,
                            title, media_type, normalized_text, source_content_sha256, metadata_json
                        ) VALUES (
                            :id, :organizationId, :documentId, :versionNumber,
                            :title, :mediaType, :normalizedText, :sha256, '{}'
                        )
                        """)
                .param("id", versionId)
                .param("organizationId", organizationId.value())
                .param("documentId", documentId.value())
                .param("versionNumber", versionNumber)
                .param("title", truncate(extraction.title(), 255))
                .param("mediaType", truncate(extraction.mediaType(), 160))
                .param("normalizedText", extraction.normalizedText())
                .param("sha256", sourceSha256)
                .update();
        jdbcClient.sql("""
                        UPDATE documents
                        SET status = 'ELIGIBLE', current_version_id = :versionId,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId AND id = :documentId
                        """)
                .param("versionId", versionId)
                .param("organizationId", organizationId.value())
                .param("documentId", documentId.value())
                .update();
        return documentId;
    }

    @Override
    @Transactional
    public void removeUnreferenced(OrganizationId organizationId, java.util.List<DocumentId> documentIds) {
        for (DocumentId documentId : documentIds) {
            int references = jdbcClient.sql("""
                            SELECT COUNT(*) FROM documents_by_connector_credential_pair
                            WHERE organization_id = :organizationId AND document_id = :documentId
                            """)
                    .param("organizationId", organizationId.value())
                    .param("documentId", documentId.value())
                    .query(Integer.class)
                    .single();
            if (references == 0) {
                jdbcClient.sql("""
                                UPDATE documents SET current_version_id = NULL, status = 'INELIGIBLE'
                                WHERE organization_id = :organizationId AND id = :documentId
                                """)
                        .param("organizationId", organizationId.value())
                        .param("documentId", documentId.value())
                        .update();
                jdbcClient.sql("""
                                DELETE FROM document_versions
                                WHERE organization_id = :organizationId AND document_id = :documentId
                                """)
                        .param("organizationId", organizationId.value())
                        .param("documentId", documentId.value())
                        .update();
                jdbcClient.sql("""
                                DELETE FROM documents
                                WHERE organization_id = :organizationId AND id = :documentId
                                """)
                        .param("organizationId", organizationId.value())
                        .param("documentId", documentId.value())
                        .update();
            }
        }
    }

    private static String truncate(String value, int limit) {
        Objects.requireNonNull(value, "value must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            normalized = "Untitled document";
        }
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }
}
