package io.memoryos.document.persistence;

import io.memoryos.document.DocumentChanged;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcDocumentRepository implements DocumentCommandPort {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;

    public JdbcDocumentRepository(JdbcClient jdbcClient, ObjectMapper objectMapper,
            ApplicationEventPublisher events) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.events = events;
    }

    @Override
    @Transactional
    public DocumentId publish(TenantId tenantId, @Nullable DocumentId existingDocumentId,
            DocumentContent content, String sourceSha256) {
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(content);
        Objects.requireNonNull(sourceSha256);
        DocumentId id = existingDocumentId == null ? new DocumentId(UUID.randomUUID()) : existingDocumentId;
        if (existingDocumentId == null) {
            jdbcClient.sql("INSERT INTO documents(id,tenant_id,status) VALUES(:id,:tenant,'ELIGIBLE')")
                    .param("id", id.value()).param("tenant", tenantId.value()).update();
        } else if (jdbcClient.sql("SELECT id FROM documents WHERE tenant_id=:tenant AND id=:id FOR UPDATE")
                .param("tenant", tenantId.value()).param("id", id.value()).query(UUID.class).optional().isEmpty()) {
            throw new IllegalStateException("mapped document is missing");
        }
        if (content.extractionArtifactId() != null) {
            int adopted = jdbcClient.sql("""
                    UPDATE document_extraction_artifacts SET state='ACTIVE'
                    WHERE tenant_id=:tenant AND id=:artifact AND state='STAGED'
                      AND write_complete=TRUE AND expires_at>CURRENT_TIMESTAMP
                    """).param("tenant", tenantId.value()).param("artifact", content.extractionArtifactId()).update();
            if (adopted != 1) throw new IllegalStateException("extraction artifact is not adoptable");
        }
        // Caller commits reference replacement and token-fenced operation completion together.
        // The old artifact is eligible for cleanup only after this transaction commits.
        UUID generation = UUID.randomUUID();
        jdbcClient.sql("""
                UPDATE documents SET status='ELIGIBLE', title=:title, media_type=:mediaType,
                    source_content_sha256=:sha, metadata_json=:metadata, extraction_artifact_id=:artifact,
                    content_generation=:generation,chunk_count=NULL,chunk_generation=NULL,
                    searchable_generation=NULL,search_index_identity=NULL,search_error_code=NULL,chunk_convention=NULL,
                    updated_at=CURRENT_TIMESTAMP
                WHERE tenant_id=:tenant AND id=:id
                """).param("title", truncate(content.title(), 255)).param("mediaType", truncate(content.mediaType(), 160))
                .param("sha", sourceSha256).param("metadata", objectMapper.writeValueAsString(content.metadata()))
                .param("artifact", content.extractionArtifactId()).param("tenant", tenantId.value())
                .param("generation", generation)
                .param("id", id.value()).update();
        events.publishEvent(new DocumentChanged(tenantId, id, generation, false));
        return id;
    }

    @Override
    @Transactional
    public void removeUnreferenced(TenantId tenantId, List<DocumentId> documentIds) {
        if (documentIds.isEmpty()) return;
        List<UUID> removed = jdbcClient.sql("""
                DELETE FROM documents WHERE tenant_id=:tenant AND id IN (:ids)
                AND NOT EXISTS (SELECT 1 FROM documents_by_connector_credential_pair mapping
                    WHERE mapping.tenant_id=:tenant AND mapping.document_id=documents.id)
                RETURNING id
                """).param("tenant", tenantId.value())
                .param("ids", documentIds.stream().map(DocumentId::value).toList()).query(UUID.class).list();
        removed.forEach(id -> events.publishEvent(new DocumentChanged(tenantId, new DocumentId(id), UUID.randomUUID(), true)));
    }

    private static String truncate(String value, int limit) {
        String normalized = Objects.requireNonNull(value).strip();
        if (normalized.isEmpty()) normalized = "Untitled document";
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }
}
