package io.memoryos.connector.persistence;

import io.memoryos.connector.IndexWork;
import io.memoryos.connector.DocumentSourceMetadata;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.sql.Types;
import tools.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceDocumentRepository {
    private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();
    private static final String SEARCHABLE_SOURCE = """
            (c.connector_type='FILE'
             OR (c.connector_type='GOOGLE_DRIVE' AND p.access_type='RESTRICTED'))
            """;
    private static final String READ_SCOPE = """
            EXISTS (
                SELECT 1 FROM tenant_memberships reader
                JOIN tenants tenant ON tenant.id=reader.tenant_id AND tenant.status='ACTIVE'
                WHERE reader.tenant_id=p.tenant_id AND reader.actor_id=:actor AND reader.status='ACTIVE'
            )
            AND (p.access_type='PUBLIC' OR EXISTS (
                SELECT 1 FROM source_group_grants grant_row
                JOIN iam_group_memberships member ON member.tenant_id=grant_row.tenant_id
                    AND member.group_id=grant_row.group_id AND member.actor_id=:actor
                WHERE grant_row.tenant_id=p.tenant_id AND grant_row.connector_credential_pair_id=p.id
            ))
            """;

    private final JdbcClient jdbcClient;

    public JdbcSourceDocumentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public boolean hasEligibleMapping(TenantId tenantId, ActorId actor, DocumentId documentId) {
        return readableDocuments(tenantId, actor, List.of(documentId.value())).contains(documentId.value());
    }

    public Optional<DocumentId> findMappedDocument(IndexWork work) {
        return jdbcClient.sql("""
                        SELECT document_id FROM documents_by_connector_credential_pair
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("pairId", work.sourceId().value())
                .param("itemId", work.itemId().value())
                .query(UUID.class)
                .optional()
                .map(DocumentId::new);
    }

    public Set<UUID> readableDocuments(TenantId tenant, ActorId actor, List<UUID> documents) {
        if (documents.isEmpty()) return Set.of();
        if (documents.size() > 1000) throw new IllegalArgumentException("document batch exceeds 1000");
        return Set.copyOf(jdbcClient.sql("""
                SELECT DISTINCT m.document_id FROM documents_by_connector_credential_pair m
                JOIN connector_credential_pairs p ON p.tenant_id=m.tenant_id AND p.id=m.connector_credential_pair_id
                JOIN connectors c ON c.tenant_id=m.tenant_id AND c.id=m.connector_id
                JOIN documents d ON d.tenant_id=m.tenant_id AND d.id=m.document_id
                WHERE m.tenant_id=:tenant AND m.document_id IN (:documents) AND m.retrieval_eligible=TRUE
                    AND p.status='ACTIVE' AND c.status='ACTIVE' AND %s AND d.status='ELIGIBLE'
                    AND %s
                """.formatted(SEARCHABLE_SOURCE, READ_SCOPE)).param("tenant", tenant.value()).param("actor", actor.value())
                .param("documents", documents).query(UUID.class).list());
    }

    public Map<UUID, SourceType> searchableSources(TenantId tenant, ActorId actor) {
        var result = new LinkedHashMap<UUID, SourceType>();
        jdbcClient.sql("""
                SELECT p.id,c.connector_type FROM connector_credential_pairs p
                JOIN connectors c ON c.tenant_id=p.tenant_id AND c.id=p.connector_id
                WHERE p.tenant_id=:tenant AND p.status='ACTIVE'
                    AND c.status='ACTIVE' AND %s AND %s
                ORDER BY p.id
                """.formatted(SEARCHABLE_SOURCE, READ_SCOPE)).param("tenant", tenant.value()).param("actor", actor.value()).query((rs, _) -> {
                    result.put(rs.getObject("id", UUID.class), SourceType.valueOf(rs.getString("connector_type")));
                    return true;
                }).list();
        return Map.copyOf(result);
    }

    public List<io.memoryos.connector.SourceSearchService.SourceOption> searchableSourceOptions(TenantId tenant, ActorId actor, int offset, int limit) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) throw new IllegalArgumentException("source page out of bounds");
        return jdbcClient.sql("""
                SELECT p.id,c.name,c.connector_type FROM connector_credential_pairs p
                JOIN connectors c ON c.tenant_id=p.tenant_id AND c.id=p.connector_id
                WHERE p.tenant_id=:tenant AND p.status='ACTIVE'
                    AND c.status='ACTIVE' AND %s AND %s
                ORDER BY c.name,p.id LIMIT :limit OFFSET :offset
                """.formatted(SEARCHABLE_SOURCE, READ_SCOPE)).param("tenant", tenant.value()).param("actor", actor.value())
                .param("offset", offset).param("limit", limit)
                .query((rs, _) -> new io.memoryos.connector.SourceSearchService.SourceOption(rs.getObject("id", UUID.class),
                        rs.getString("name"), SourceType.valueOf(rs.getString("connector_type")))).list();
    }

    public Map<UUID, List<DocumentSourceMetadata>> sourceMetadata(TenantId tenant, List<UUID> ids,
            @Nullable ActorId actor, @Nullable UUID generation) {
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > 1000) throw new IllegalArgumentException("document batch exceeds 1000");
        var result = new LinkedHashMap<UUID, List<DocumentSourceMetadata>>();
        // Keep each source/item/date tuple together, including when a document has multiple mappings.
        jdbcClient.sql("""
                SELECT m.document_id,p.id AS source_id,i.id AS item_id,c.connector_type,
                    i.source_created_at,i.source_updated_at,d.metadata_json
                FROM documents_by_connector_credential_pair m
                JOIN connector_credential_pairs p ON p.tenant_id=m.tenant_id AND p.id=m.connector_credential_pair_id
                JOIN connectors c ON c.tenant_id=m.tenant_id AND c.id=m.connector_id
                JOIN connector_items i ON i.tenant_id=m.tenant_id AND i.id=m.connector_item_id
                JOIN documents d ON d.tenant_id=m.tenant_id AND d.id=m.document_id
                WHERE m.tenant_id=:tenant AND m.document_id IN (:documents) AND m.retrieval_eligible=TRUE
                    AND d.status='ELIGIBLE' AND (:anyGeneration OR d.content_generation=:generation)
                    AND c.status='ACTIVE' AND %s AND p.status<>'DELETING'
                    AND (:indexing OR (p.status='ACTIVE' AND %s))
                ORDER BY m.document_id,p.id,i.id
                """.formatted(SEARCHABLE_SOURCE, READ_SCOPE)).param("tenant", tenant.value()).param("documents", ids).param("indexing", actor == null)
                .param("actor", actor == null ? null : actor.value(), Types.OTHER)
                .param("anyGeneration", generation == null).param("generation", generation, Types.OTHER)
                .query((rs, _) -> {
                    var created = rs.getTimestamp("source_created_at");
                    var updated = rs.getTimestamp("source_updated_at");
                    var metadata = new DocumentSourceMetadata(rs.getObject("source_id", UUID.class),
                            rs.getObject("item_id", UUID.class), SourceType.valueOf(rs.getString("connector_type")),
                            created == null ? null : created.toInstant(), updated == null ? null : updated.toInstant(),
                            authors(rs.getString("metadata_json")));
                    result.computeIfAbsent(rs.getObject("document_id", UUID.class), _ -> new ArrayList<>()).add(metadata);
                    return true;
                }).list();
        return Map.copyOf(result);
    }

    private static List<String> authors(@Nullable String json) {
        if (json == null) return List.of();
        try {
            var metadata = METADATA_MAPPER.readTree(json);
            for (String key : List.of("dc:creator", "Author", "author", "creator")) {
                String value = metadata.path(key).asString("").strip();
                if (!value.isEmpty()) return List.of(value.substring(0, Math.min(value.length(), 512)));
            }
        } catch (tools.jackson.core.JacksonException malformed) {
            // Optional author metadata must not make an otherwise eligible document unavailable.
            return List.of();
        }
        return List.of();
    }

    public void publishMapping(IndexWork work, DocumentId documentId) {
        int updated = jdbcClient.sql("""
                        UPDATE documents_by_connector_credential_pair
                        SET document_id = :documentId, retrieval_eligible = TRUE,
                            last_indexed_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                        """)
                .param("documentId", documentId.value())
                .param("tenantId", work.tenantId().value())
                .param("pairId", work.sourceId().value())
                .param("itemId", work.itemId().value())
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                            INSERT INTO documents_by_connector_credential_pair (
                                tenant_id, connector_id, connector_credential_pair_id,
                                document_id, connector_item_id, retrieval_eligible
                            ) VALUES (
                                :tenantId, :connectorId, :pairId,
                                :documentId, :itemId, TRUE
                            )
                            """)
                    .param("tenantId", work.tenantId().value())
                    .param("connectorId", work.connectorId())
                    .param("pairId", work.sourceId().value())
                    .param("documentId", documentId.value())
                    .param("itemId", work.itemId().value())
                    .update();
        }
    }

    public void invalidateItem(TenantId tenantId, SourceId sourceId, SourceItemId itemId) {
        jdbcClient.sql("""
                        UPDATE documents_by_connector_credential_pair
                        SET retrieval_eligible = FALSE, last_indexed_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .param("itemId", itemId.value())
                .update();
    }

    public void invalidateSource(TenantId tenantId, SourceId sourceId) {
        jdbcClient.sql("""
                        UPDATE documents_by_connector_credential_pair
                        SET retrieval_eligible = FALSE, last_indexed_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .update();
    }

    public List<UUID> removeItemMappings(
            TenantId tenantId,
            SourceId sourceId,
            SourceItemId itemId
    ) {
        List<UUID> documents = documentIds(tenantId, sourceId, itemId);
        jdbcClient.sql("""
                        DELETE FROM documents_by_connector_credential_pair
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .param("itemId", itemId.value())
                .update();
        return documents;
    }

    public List<UUID> removeSourceMappings(TenantId tenantId, SourceId sourceId) {
        List<UUID> documents = documentIds(tenantId, sourceId, null);
        jdbcClient.sql("""
                        DELETE FROM documents_by_connector_credential_pair
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .update();
        return documents;
    }

    private List<UUID> documentIds(
            TenantId tenantId,
            SourceId sourceId,
            @Nullable SourceItemId itemId
    ) {
        var statement = jdbcClient.sql(itemId == null ? """
                        SELECT document_id FROM documents_by_connector_credential_pair
                        WHERE tenant_id = :tenantId AND connector_credential_pair_id = :pairId
                        """ : """
                        SELECT document_id FROM documents_by_connector_credential_pair
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value());
        if (itemId != null) {
            statement = statement.param("itemId", itemId.value());
        }
        return statement.query(UUID.class).list();
    }
}
