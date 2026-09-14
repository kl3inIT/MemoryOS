package io.memoryos.connector.persistence;

import io.memoryos.connector.DocumentAccess;
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
    private static final String SEARCHABLE_SOURCE = "c.connector_type IN ('FILE','GOOGLE_DRIVE')";
    /** A provider grant that admits every active Tenant member; it is indexed as {@code access_public}. */
    private static final String PUBLIC_GRANT = "everyone";
    /**
     * Grant tokens of the SYNC mapping {@code m} of pair {@code p}: the last successful Google Drive permission
     * snapshot of the mapped file, interpreted as in the MEM-105 design. Index-time access and the post-query
     * recheck both use this rule, so they cannot drift.
     */
    private static final String SYNC_GRANTS = """
            SELECT CASE entry.permission->>'type'
                     WHEN 'user' THEN 'google_user:' || LOWER(entry.permission->>'emailAddress')
                     WHEN 'domain' THEN 'google_domain:' || LOWER(entry.permission->>'domain')
                     ELSE 'everyone' END AS token
            FROM connector_items sync_item
            JOIN google_drive_acl_snapshots snapshot ON snapshot.tenant_id=sync_item.tenant_id
                AND snapshot.source_id=p.id AND snapshot.file_id=sync_item.provider_file_id
                AND snapshot.observation_revision>0
            CROSS JOIN LATERAL jsonb_array_elements(snapshot.permissions_json) AS entry(permission)
            WHERE sync_item.tenant_id=m.tenant_id AND sync_item.id=m.connector_item_id
                AND COALESCE(entry.permission->>'deleted','false')<>'true'
                AND (entry.permission->>'expirationTime' IS NULL
                    OR CAST(entry.permission->>'expirationTime' AS TIMESTAMPTZ)>statement_timestamp())
                AND ((entry.permission->>'type'='user' AND BTRIM(COALESCE(entry.permission->>'emailAddress',''))<>'')
                    OR (entry.permission->>'type'='domain' AND BTRIM(COALESCE(entry.permission->>'domain',''))<>''
                        AND COALESCE(entry.permission->>'allowFileDiscovery','true')<>'false')
                    OR entry.permission->>'type'='anyone')
            """;
    /** The reader's provider identities: the verified login email and its domain. An unverified email grants nothing. */
    private static final String READER_TOKENS = """
            SELECT 'google_user:' || LOWER(profile.email) AS token FROM actor_profiles profile
            WHERE profile.actor_id=:actor AND profile.email_verified AND profile.email ~ '^[^@[:space:]]+@[^@[:space:]]+$'
            UNION ALL
            SELECT 'google_domain:' || LOWER(SPLIT_PART(profile.email,'@',2)) FROM actor_profiles profile
            WHERE profile.actor_id=:actor AND profile.email_verified AND profile.email ~ '^[^@[:space:]]+@[^@[:space:]]+$'
            """;
    private static final String READ_SCOPE = """
            EXISTS (
                SELECT 1 FROM tenant_memberships reader
                JOIN tenants tenant ON tenant.id=reader.tenant_id AND tenant.status='ACTIVE'
                WHERE reader.tenant_id=p.tenant_id AND reader.actor_id=:actor AND reader.status='ACTIVE'
            )
            AND (p.access_type='PUBLIC'
                OR (p.access_type='PRIVATE' AND EXISTS (
                    SELECT 1 FROM source_group_grants grant_row
                    JOIN iam_group_memberships member ON member.tenant_id=grant_row.tenant_id
                        AND member.group_id=grant_row.group_id AND member.actor_id=:actor
                    WHERE grant_row.tenant_id=p.tenant_id AND grant_row.connector_credential_pair_id=p.id
                ))
                OR (p.access_type='SYNC' AND %s))
            """;
    /** Document reads recheck the mapped file's provider grants against the reader's identities. */
    private static final String DOCUMENT_READ_SCOPE = READ_SCOPE.formatted("EXISTS (SELECT 1 FROM (" + SYNC_GRANTS
            + ") sync_grant WHERE sync_grant.token='" + PUBLIC_GRANT + "' OR sync_grant.token IN (" + READER_TOKENS + "))");
    /** Source lists show SYNC Sources to active members; each of their documents is still rechecked. */
    private static final String SOURCE_READ_SCOPE = READ_SCOPE.formatted("TRUE");

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
                    AND p.status IN ('ACTIVE','INDEXING') AND c.status='ACTIVE' AND %s AND d.status='ELIGIBLE'
                    AND %s
                """.formatted(SEARCHABLE_SOURCE, DOCUMENT_READ_SCOPE)).param("tenant", tenant.value()).param("actor", actor.value())
                .param("documents", documents).query(UUID.class).list());
    }

    public Map<UUID, SourceType> searchableSources(TenantId tenant, ActorId actor) {
        var result = new LinkedHashMap<UUID, SourceType>();
        jdbcClient.sql("""
                SELECT p.id,c.connector_type FROM connector_credential_pairs p
                JOIN connectors c ON c.tenant_id=p.tenant_id AND c.id=p.connector_id
                WHERE p.tenant_id=:tenant AND p.status IN ('ACTIVE','INDEXING')
                    AND c.status='ACTIVE' AND %s AND %s
                ORDER BY p.id
                """.formatted(SEARCHABLE_SOURCE, SOURCE_READ_SCOPE)).param("tenant", tenant.value()).param("actor", actor.value()).query((rs, _) -> {
                    result.put(rs.getObject("id", UUID.class), SourceType.valueOf(rs.getString("connector_type")));
                    return true;
                }).list();
        return Map.copyOf(result);
    }

    /**
     * Index-time access over the same mappings that index metadata uses; no mapping means no access. PUBLIC admits
     * everyone, PRIVATE its Group tokens and SYNC the provider grants of the mapped file.
     */
    public DocumentAccess documentAccess(TenantId tenant, UUID document) {
        var rows = jdbcClient.sql("""
                SELECT p.access_type,grant_row.group_id,CAST(NULL AS TEXT) AS token FROM documents_by_connector_credential_pair m
                JOIN connector_credential_pairs p ON p.tenant_id=m.tenant_id AND p.id=m.connector_credential_pair_id
                JOIN connectors c ON c.tenant_id=m.tenant_id AND c.id=m.connector_id
                LEFT JOIN source_group_grants grant_row ON grant_row.tenant_id=p.tenant_id
                    AND grant_row.connector_credential_pair_id=p.id AND p.access_type='PRIVATE'
                WHERE m.tenant_id=:tenant AND m.document_id=:document AND m.retrieval_eligible=TRUE
                    AND c.status='ACTIVE' AND %1$s AND p.status<>'DELETING' AND p.access_type<>'SYNC'
                UNION ALL
                SELECT p.access_type,CAST(NULL AS UUID),sync_grant.token FROM documents_by_connector_credential_pair m
                JOIN connector_credential_pairs p ON p.tenant_id=m.tenant_id AND p.id=m.connector_credential_pair_id
                JOIN connectors c ON c.tenant_id=m.tenant_id AND c.id=m.connector_id
                CROSS JOIN LATERAL (%2$s) sync_grant
                WHERE m.tenant_id=:tenant AND m.document_id=:document AND m.retrieval_eligible=TRUE
                    AND c.status='ACTIVE' AND %1$s AND p.status<>'DELETING' AND p.access_type='SYNC'
                """.formatted(SEARCHABLE_SOURCE, SYNC_GRANTS)).param("tenant", tenant.value()).param("document", document)
                .query((rs, _) -> new AccessRow(rs.getString("access_type"), rs.getObject("group_id", UUID.class),
                        rs.getString("token"))).list();
        boolean everyone = rows.stream().anyMatch(row -> "PUBLIC".equals(row.accessType()) || PUBLIC_GRANT.equals(row.token()));
        var tokens = new java.util.HashSet<String>();
        for (var row : rows) {
            if (row.groupId() != null) tokens.add(DocumentAccess.group(row.groupId()));
            if (row.token() != null && !PUBLIC_GRANT.equals(row.token())) tokens.add(row.token());
        }
        return new DocumentAccess(everyone, tokens);
    }

    /** The reader's current Group tokens and verified provider identities; an inactive membership yields none. */
    public Set<String> actorAccessTokens(TenantId tenant, ActorId actor) {
        var tokens = new java.util.HashSet<String>();
        jdbcClient.sql("""
                SELECT member.group_id FROM iam_group_memberships member
                JOIN tenant_memberships reader ON reader.tenant_id=member.tenant_id AND reader.actor_id=member.actor_id
                    AND reader.status='ACTIVE'
                WHERE member.tenant_id=:tenant AND member.actor_id=:actor
                """).param("tenant", tenant.value()).param("actor", actor.value()).query(UUID.class).list()
                .forEach(group -> tokens.add(DocumentAccess.group(group)));
        tokens.addAll(jdbcClient.sql("""
                SELECT reader_token.token FROM (%s) reader_token
                WHERE EXISTS (SELECT 1 FROM tenant_memberships reader
                    WHERE reader.tenant_id=:tenant AND reader.actor_id=:actor AND reader.status='ACTIVE')
                """.formatted(READER_TOKENS)).param("tenant", tenant.value()).param("actor", actor.value())
                .query(String.class).list());
        return Set.copyOf(tokens);
    }

    private record AccessRow(String accessType, @Nullable UUID groupId, @Nullable String token) { }

    public List<io.memoryos.connector.SourceSearchService.SourceOption> searchableSourceOptions(TenantId tenant, ActorId actor, int offset, int limit) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) throw new IllegalArgumentException("source page out of bounds");
        return jdbcClient.sql("""
                SELECT p.id,c.name,c.connector_type FROM connector_credential_pairs p
                JOIN connectors c ON c.tenant_id=p.tenant_id AND c.id=p.connector_id
                WHERE p.tenant_id=:tenant AND p.status IN ('ACTIVE','INDEXING')
                    AND c.status='ACTIVE' AND %s AND %s
                ORDER BY c.name,p.id LIMIT :limit OFFSET :offset
                """.formatted(SEARCHABLE_SOURCE, SOURCE_READ_SCOPE)).param("tenant", tenant.value()).param("actor", actor.value())
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
                    i.source_created_at,i.source_updated_at,i.provider_file_id,d.metadata_json
                FROM documents_by_connector_credential_pair m
                JOIN connector_credential_pairs p ON p.tenant_id=m.tenant_id AND p.id=m.connector_credential_pair_id
                JOIN connectors c ON c.tenant_id=m.tenant_id AND c.id=m.connector_id
                JOIN connector_items i ON i.tenant_id=m.tenant_id AND i.id=m.connector_item_id
                JOIN documents d ON d.tenant_id=m.tenant_id AND d.id=m.document_id
                WHERE m.tenant_id=:tenant AND m.document_id IN (:documents) AND m.retrieval_eligible=TRUE
                    AND d.status='ELIGIBLE' AND (:anyGeneration OR d.content_generation=:generation OR d.searchable_generation=:generation)
                    AND c.status='ACTIVE' AND %s AND p.status<>'DELETING'
                    AND (:indexing OR (p.status IN ('ACTIVE','INDEXING') AND %s))
                ORDER BY m.document_id,p.id,i.id
                """.formatted(SEARCHABLE_SOURCE, DOCUMENT_READ_SCOPE)).param("tenant", tenant.value()).param("documents", ids).param("indexing", actor == null)
                .param("actor", actor == null ? null : actor.value(), Types.OTHER)
                .param("anyGeneration", generation == null).param("generation", generation, Types.OTHER)
                .query((rs, _) -> {
                    var created = rs.getTimestamp("source_created_at");
                    var updated = rs.getTimestamp("source_updated_at");
                    var metadata = new DocumentSourceMetadata(rs.getObject("source_id", UUID.class),
                            rs.getObject("item_id", UUID.class), SourceType.valueOf(rs.getString("connector_type")),
                            created == null ? null : created.toInstant(), updated == null ? null : updated.toInstant(),
                            authors(rs.getString("metadata_json")), rs.getString("provider_file_id"));
                    result.computeIfAbsent(rs.getObject("document_id", UUID.class), _ -> new ArrayList<>()).add(metadata);
                    return true;
                }).list();
        return Map.copyOf(result);
    }

    /**
     * Stored original PDF behind an actor-readable mapping whose current item version produced the Document's
     * current source content. Other media types and stale versions have no original to serve.
     */
    public Optional<io.memoryos.objectstorage.StoredObjectReference> originalPdf(TenantId tenant, ActorId actor, UUID document) {
        return jdbcClient.sql("""
                SELECT o.id,o.object_key,o.filename,o.size_bytes,o.declared_media_type,o.content_sha256
                FROM documents_by_connector_credential_pair m
                JOIN connector_credential_pairs p ON p.tenant_id=m.tenant_id AND p.id=m.connector_credential_pair_id
                JOIN connectors c ON c.tenant_id=m.tenant_id AND c.id=m.connector_id
                JOIN connector_items i ON i.tenant_id=m.tenant_id AND i.id=m.connector_item_id
                JOIN connector_item_versions v ON v.tenant_id=i.tenant_id AND v.id=i.current_version_id
                JOIN stored_objects o ON o.tenant_id=v.tenant_id AND o.id=v.stored_object_id AND o.state='ACTIVE'
                JOIN documents d ON d.tenant_id=m.tenant_id AND d.id=m.document_id
                WHERE m.tenant_id=:tenant AND m.document_id=:document AND m.retrieval_eligible=TRUE
                    AND d.status='ELIGIBLE' AND d.media_type='application/pdf'
                    AND d.source_content_sha256=v.content_sha256
                    AND c.status='ACTIVE' AND %s AND p.status='ACTIVE' AND %s
                ORDER BY p.id,i.id
                LIMIT 1
                """.formatted(SEARCHABLE_SOURCE, DOCUMENT_READ_SCOPE)).param("tenant", tenant.value()).param("document", document)
                .param("actor", actor.value())
                .query((r, _) -> new io.memoryos.objectstorage.StoredObjectReference(
                        new io.memoryos.objectstorage.StoredObjectId(r.getObject("id", UUID.class)),
                        new io.memoryos.objectstorage.ObjectKey(r.getString("object_key")), r.getString("filename"),
                        new io.memoryos.objectstorage.ObjectMetadata(r.getLong("size_bytes"), r.getString("declared_media_type"),
                                new io.memoryos.objectstorage.ContentSha256(r.getString("content_sha256")))))
                .optional();
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
