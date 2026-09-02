package io.memoryos.document.persistence;

import io.memoryos.document.DocumentCommandPort;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.DocumentId;
import io.memoryos.tenant.TenantId;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcDocumentRepository implements DocumentCommandPort {

    private static final String UNREFERENCED = """
            NOT EXISTS (
                SELECT 1 FROM documents_by_connector_credential_pair mapping
                WHERE mapping.tenant_id = :tenantId AND mapping.document_id = %s
            )
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcDocumentRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional
    public DocumentId publish(
            TenantId tenantId,
            @Nullable DocumentId existingDocumentId,
            DocumentContent content,
            String sourceSha256
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(sourceSha256, "sourceSha256 must not be null");
        DocumentId documentId = existingDocumentId == null
                ? new DocumentId(UUID.randomUUID())
                : existingDocumentId;
        if (existingDocumentId == null) {
            jdbcClient.sql("""
                            INSERT INTO documents (id, tenant_id, status)
                            VALUES (:id, :tenantId, 'ELIGIBLE')
                            """)
                    .param("id", documentId.value())
                    .param("tenantId", tenantId.value())
                    .update();
        } else {
            boolean locked = jdbcClient.sql("""
                            SELECT id FROM documents
                            WHERE tenant_id = :tenantId AND id = :id
                            FOR UPDATE
                            """)
                    .param("tenantId", tenantId.value())
                    .param("id", documentId.value())
                    .query(UUID.class)
                    .optional()
                    .isPresent();
            if (!locked) {
                throw new IllegalStateException("mapped document is missing");
            }
            boolean sameContent = jdbcClient.sql("""
                            SELECT COUNT(*) FROM document_versions
                            WHERE tenant_id = :tenantId
                              AND document_id = :documentId
                              AND source_content_sha256 = :sha256
                            """)
                    .param("tenantId", tenantId.value())
                    .param("documentId", documentId.value())
                    .param("sha256", sourceSha256)
                    .query(Integer.class)
                    .single() != 0;
            if (sameContent) {
                jdbcClient.sql("""
                                UPDATE documents
                                SET status = 'ELIGIBLE', updated_at = CURRENT_TIMESTAMP
                                WHERE tenant_id = :tenantId AND id = :documentId
                                """)
                        .param("tenantId", tenantId.value())
                        .param("documentId", documentId.value())
                        .update();
                return documentId;
            }
        }

        Long versionNumber = jdbcClient.sql("""
                        SELECT COALESCE(MAX(version_number), 0) + 1
                        FROM document_versions
                        WHERE tenant_id = :tenantId AND document_id = :documentId
                        """)
                .param("tenantId", tenantId.value())
                .param("documentId", documentId.value())
                .query(Long.class)
                .single();
        UUID versionId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO document_versions (
                            id, tenant_id, document_id, version_number,
                            title, media_type, normalized_text, source_content_sha256, metadata_json
                        ) VALUES (
                            :id, :tenantId, :documentId, :versionNumber,
                            :title, :mediaType, :normalizedText, :sha256, :metadata
                        )
                        """)
                .param("id", versionId)
                .param("tenantId", tenantId.value())
                .param("documentId", documentId.value())
                .param("versionNumber", versionNumber)
                .param("title", truncate(content.title(), 255))
                .param("mediaType", truncate(content.mediaType(), 160))
                .param("normalizedText", content.normalizedText())
                .param("sha256", sourceSha256)
                .param("metadata", objectMapper.writeValueAsString(content.metadata()))
                .update();
        jdbcClient.sql("""
                        UPDATE documents
                        SET status = 'ELIGIBLE', current_version_id = :versionId,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :documentId
                        """)
                .param("versionId", versionId)
                .param("tenantId", tenantId.value())
                .param("documentId", documentId.value())
                .update();
        return documentId;
    }

    @Override
    @Transactional
    public void removeUnreferenced(TenantId tenantId, List<DocumentId> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }
        List<UUID> ids = documentIds.stream().map(DocumentId::value).toList();
        jdbcClient.sql("""
                        UPDATE documents SET current_version_id = NULL, status = 'INELIGIBLE'
                        WHERE tenant_id = :tenantId AND id IN (:ids) AND
                        """ + UNREFERENCED.formatted("documents.id"))
                .param("tenantId", tenantId.value())
                .param("ids", ids)
                .update();
        jdbcClient.sql("""
                        DELETE FROM document_versions
                        WHERE tenant_id = :tenantId AND document_id IN (:ids) AND
                        """ + UNREFERENCED.formatted("document_versions.document_id"))
                .param("tenantId", tenantId.value())
                .param("ids", ids)
                .update();
        jdbcClient.sql("""
                        DELETE FROM documents
                        WHERE tenant_id = :tenantId AND id IN (:ids) AND
                        """ + UNREFERENCED.formatted("documents.id"))
                .param("tenantId", tenantId.value())
                .param("ids", ids)
                .update();
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
