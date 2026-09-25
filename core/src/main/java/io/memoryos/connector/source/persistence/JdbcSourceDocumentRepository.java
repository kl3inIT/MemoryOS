package io.memoryos.connector.source.persistence;

import io.memoryos.connector.DocumentAccess;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceDocumentBrowse;
import io.memoryos.connector.SourceDocumentEntry;
import io.memoryos.connector.IndexWork;
import io.memoryos.connector.DocumentSourceMetadata;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.document.DocumentId;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.FileCategorySql;
import io.memoryos.shared.LikePattern;
import io.memoryos.shared.TenantId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import tools.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceDocumentRepository {
    private static final ObjectMapper METADATA_MAPPER = new ObjectMapper();
    private static final String SEARCHABLE_SOURCE = "c.connector_type IN ('FILE','GOOGLE_DRIVE','SHAREPOINT')";
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
                     WHEN 'group' THEN 'google_group:' || LOWER(entry.permission->>'emailAddress')
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
                AND ((entry.permission->>'type' IN ('user','group') AND BTRIM(COALESCE(entry.permission->>'emailAddress',''))<>'')
                    OR (entry.permission->>'type'='domain' AND BTRIM(COALESCE(entry.permission->>'domain',''))<>''
                        AND COALESCE(entry.permission->>'allowFileDiscovery','true')<>'false')
                    OR entry.permission->>'type'='anyone')
            """;
    /**
     * The reader's provider identities in {@code :tenant}: the verified login email, its domain, and the Google
     * Groups that list it in the active generation of an active service-account credential. A group whose members
     * include the whole customer admits every reader of the primary admin's domain. An unverified email grants nothing.
     */
    private static final String READER_TOKENS = """
            SELECT 'google_user:' || LOWER(profile.email) AS token FROM actor_profiles profile
            WHERE profile.actor_id=:actor AND profile.email_verified AND profile.email ~ '^[^@[:space:]]+@[^@[:space:]]+$'
            UNION ALL
            SELECT 'google_domain:' || LOWER(SPLIT_PART(profile.email,'@',2)) FROM actor_profiles profile
            WHERE profile.actor_id=:actor AND profile.email_verified AND profile.email ~ '^[^@[:space:]]+@[^@[:space:]]+$'
            UNION ALL
            SELECT 'google_group:' || grp.group_email FROM actor_profiles profile
            JOIN google_drive_credentials g ON g.tenant_id=:tenant AND g.active_group_generation IS NOT NULL
                AND g.connection_status='ACTIVE' AND g.auth_method='SERVICE_ACCOUNT'
            JOIN credentials credential ON credential.tenant_id=g.tenant_id AND credential.id=g.credential_id
                AND credential.status='ACTIVE'
            JOIN google_group_sync_groups grp ON grp.tenant_id=g.tenant_id AND grp.credential_id=g.credential_id
                AND grp.generation=g.active_group_generation
            WHERE profile.actor_id=:actor AND profile.email_verified AND profile.email ~ '^[^@[:space:]]+@[^@[:space:]]+$'
                AND ((grp.whole_domain
                        AND LOWER(SPLIT_PART(profile.email,'@',2))=LOWER(SPLIT_PART(g.account_email,'@',2)))
                    OR EXISTS (SELECT 1 FROM google_group_members member
                        WHERE member.tenant_id=grp.tenant_id AND member.credential_id=grp.credential_id
                            AND member.generation=grp.generation AND member.group_email=grp.group_email
                            AND member.member_email=LOWER(profile.email)))
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
    /**
     * Readable Source documents, each under its first readable mapping by Source then item. The Sources the actor
     * may read are resolved once; each mapping is then rechecked with the document scope (%4$s), which also carries
     * the provider grants of SYNC Sources. %3$s narrows the mappings, %6$s filters, orders and limits the entries.
     */
    private static final String BROWSE = """
            WITH readable_pairs AS MATERIALIZED (
                SELECT p.tenant_id, p.id, p.access_type, c.name AS source_name, c.connector_type
                FROM connector_credential_pairs p
                JOIN connectors c ON c.tenant_id=p.tenant_id AND c.id=p.connector_id
                WHERE p.tenant_id=:tenant AND p.status IN ('ACTIVE','INDEXING') AND c.status='ACTIVE' AND %1$s
                    AND (:allSources OR p.id IN (:sources)) AND %2$s
            ), mapped AS (
                SELECT DISTINCT ON (m.document_id) m.document_id, p.id AS source_id, p.source_name, p.connector_type,
                    p.access_type, v.filename, d.title,
                    COALESCE(d.media_type, o.declared_media_type, 'application/octet-stream') AS media_type, v.size_bytes,
                    COALESCE(i.source_updated_at, i.updated_at) AS updated_at, v.source_url,
                    CASE WHEN d.search_index_identity=:identity THEN d.searchable_generation END AS generation
                FROM readable_pairs p
                JOIN documents_by_connector_credential_pair m ON m.tenant_id=p.tenant_id AND m.connector_credential_pair_id=p.id
                JOIN connector_items i ON i.tenant_id=m.tenant_id AND i.id=m.connector_item_id
                JOIN connector_item_versions v ON v.tenant_id=i.tenant_id AND v.id=i.current_version_id
                LEFT JOIN stored_objects o ON o.tenant_id=v.tenant_id AND o.id=v.stored_object_id
                JOIN documents d ON d.tenant_id=m.tenant_id AND d.id=m.document_id
                WHERE m.retrieval_eligible=TRUE AND d.status='ELIGIBLE' %3$s AND %4$s
                ORDER BY m.document_id, p.id, i.id
            ), categorized AS (
                SELECT mapped.*, %5$s AS category FROM mapped
            )
            SELECT entry.*, ARRAY(
                SELECT DISTINCT grp.name FROM source_group_grants grant_row
                JOIN iam_group_memberships member ON member.tenant_id=grant_row.tenant_id
                    AND member.group_id=grant_row.group_id AND member.actor_id=:actor
                JOIN iam_groups grp ON grp.tenant_id=grant_row.tenant_id AND grp.id=grant_row.group_id
                WHERE entry.access_type='PRIVATE' AND grant_row.tenant_id=:tenant
                    AND grant_row.connector_credential_pair_id=entry.source_id
                ORDER BY grp.name) AS group_names
            FROM (SELECT entry.* FROM categorized entry %6$s) entry
            ORDER BY %7$s
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
        return documentAccess(tenant, List.of(document)).get(document);
    }

    /** {@link #documentAccess(TenantId, UUID)} for several documents in one read; every requested document has an entry. */
    public Map<UUID, DocumentAccess> documentAccess(TenantId tenant, java.util.Collection<UUID> documents) {
        if (documents.isEmpty()) return Map.of();
        if (documents.size() > 1000) throw new IllegalArgumentException("document batch exceeds 1000");
        var rows = jdbcClient.sql("""
                SELECT m.document_id,p.access_type,grant_row.group_id,CAST(NULL AS TEXT) AS token FROM documents_by_connector_credential_pair m
                JOIN connector_credential_pairs p ON p.tenant_id=m.tenant_id AND p.id=m.connector_credential_pair_id
                JOIN connectors c ON c.tenant_id=m.tenant_id AND c.id=m.connector_id
                LEFT JOIN source_group_grants grant_row ON grant_row.tenant_id=p.tenant_id
                    AND grant_row.connector_credential_pair_id=p.id AND p.access_type='PRIVATE'
                WHERE m.tenant_id=:tenant AND m.document_id IN (:documents) AND m.retrieval_eligible=TRUE
                    AND c.status='ACTIVE' AND %1$s AND p.status<>'DELETING' AND p.access_type<>'SYNC'
                UNION ALL
                SELECT m.document_id,p.access_type,CAST(NULL AS UUID),sync_grant.token FROM documents_by_connector_credential_pair m
                JOIN connector_credential_pairs p ON p.tenant_id=m.tenant_id AND p.id=m.connector_credential_pair_id
                JOIN connectors c ON c.tenant_id=m.tenant_id AND c.id=m.connector_id
                CROSS JOIN LATERAL (%2$s) sync_grant
                WHERE m.tenant_id=:tenant AND m.document_id IN (:documents) AND m.retrieval_eligible=TRUE
                    AND c.status='ACTIVE' AND %1$s AND p.status<>'DELETING' AND p.access_type='SYNC'
                """.formatted(SEARCHABLE_SOURCE, SYNC_GRANTS)).param("tenant", tenant.value()).param("documents", documents)
                .query((rs, _) -> new AccessRow(rs.getObject("document_id", UUID.class), rs.getString("access_type"),
                        rs.getObject("group_id", UUID.class), rs.getString("token"))).list();
        var byDocument = rows.stream().collect(java.util.stream.Collectors.groupingBy(AccessRow::document));
        var result = new LinkedHashMap<UUID, DocumentAccess>();
        for (UUID document : documents) {
            var own = byDocument.getOrDefault(document, List.of());
            boolean everyone = own.stream().anyMatch(row -> "PUBLIC".equals(row.accessType()) || PUBLIC_GRANT.equals(row.token()));
            var tokens = new java.util.HashSet<String>();
            for (var row : own) {
                if (row.groupId() != null) tokens.add(DocumentAccess.group(row.groupId()));
                if (row.token() != null && !PUBLIC_GRANT.equals(row.token())) tokens.add(row.token());
            }
            result.put(document, new DocumentAccess(everyone, tokens));
        }
        return Map.copyOf(result);
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

    private record AccessRow(UUID document, String accessType, @Nullable UUID groupId, @Nullable String token) { }

    public List<io.memoryos.connector.SourceSearchService.SourceOption> sourceNames(TenantId tenant, java.util.Collection<UUID> ids) {
        return jdbcClient.sql("""
                SELECT p.id,c.name,c.connector_type FROM connector_credential_pairs p
                JOIN connectors c ON c.tenant_id=p.tenant_id AND c.id=p.connector_id
                WHERE p.tenant_id=:tenant AND p.id IN (:ids)
                ORDER BY c.name,p.id
                """).param("tenant", tenant.value()).param("ids", ids)
                .query((rs, _) -> new io.memoryos.connector.SourceSearchService.SourceOption(rs.getObject("id", UUID.class),
                        rs.getString("name"), SourceType.valueOf(rs.getString("connector_type")))).list();
    }

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
        return sourceMetadata(tenant, ids, actor, generation, null);
    }

    /**
     * Index metadata of several documents in one read, each for its own generation (the content or the served one),
     * exactly as {@link #sourceMetadata} gives it for one document and generation.
     */
    public Map<UUID, List<DocumentSourceMetadata>> indexMetadata(TenantId tenant, Map<UUID, UUID> generations) {
        return sourceMetadata(tenant, List.copyOf(generations.keySet()), null, null, generations);
    }

    private Map<UUID, List<DocumentSourceMetadata>> sourceMetadata(TenantId tenant, List<UUID> ids,
            @Nullable ActorId actor, @Nullable UUID generation, @Nullable Map<UUID, UUID> generations) {
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > 1000) throw new IllegalArgumentException("document batch exceeds 1000");
        var result = new LinkedHashMap<UUID, List<DocumentSourceMetadata>>();
        // Keep each source/item/date tuple together, including when a document has multiple mappings.
        jdbcClient.sql("""
                SELECT m.document_id,p.id AS source_id,i.id AS item_id,c.connector_type,
                    i.source_created_at,i.source_updated_at,i.provider_file_id,d.metadata_json,
                    d.content_generation,d.searchable_generation
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
                    var document = rs.getObject("document_id", UUID.class);
                    if (generations != null) {
                        var wanted = generations.get(document);
                        if (!wanted.equals(rs.getObject("content_generation", UUID.class))
                                && !wanted.equals(rs.getObject("searchable_generation", UUID.class))) return false;
                    }
                    var created = rs.getTimestamp("source_created_at");
                    var updated = rs.getTimestamp("source_updated_at");
                    var metadata = new DocumentSourceMetadata(rs.getObject("source_id", UUID.class),
                            rs.getObject("item_id", UUID.class), SourceType.valueOf(rs.getString("connector_type")),
                            created == null ? null : created.toInstant(), updated == null ? null : updated.toInstant(),
                            authors(rs.getString("metadata_json")), rs.getString("provider_file_id"));
                    result.computeIfAbsent(document, _ -> new ArrayList<>()).add(metadata);
                    return true;
                }).list();
        return Map.copyOf(result);
    }

    /**
     * The stored source object each readable, eligible Document was extracted from, whatever its media type. Documents
     * the actor cannot read through an active searchable Source, or whose current version no longer matches, are absent.
     */
    public java.util.Map<UUID, io.memoryos.objectstorage.StoredObjectReference> originals(TenantId tenant, ActorId actor,
            java.util.Set<UUID> documents) {
        if (documents.isEmpty()) return java.util.Map.of();
        var result = new java.util.LinkedHashMap<UUID, io.memoryos.objectstorage.StoredObjectReference>();
        jdbcClient.sql("""
                SELECT DISTINCT ON (m.document_id) m.document_id,o.id,o.object_key,o.filename,o.size_bytes,o.declared_media_type,o.content_sha256
                FROM documents_by_connector_credential_pair m
                JOIN connector_credential_pairs p ON p.tenant_id=m.tenant_id AND p.id=m.connector_credential_pair_id
                JOIN connectors c ON c.tenant_id=m.tenant_id AND c.id=m.connector_id
                JOIN connector_items i ON i.tenant_id=m.tenant_id AND i.id=m.connector_item_id
                JOIN connector_item_versions v ON v.tenant_id=i.tenant_id AND v.id=i.current_version_id
                JOIN stored_objects o ON o.tenant_id=v.tenant_id AND o.id=v.stored_object_id AND o.state='ACTIVE'
                JOIN documents d ON d.tenant_id=m.tenant_id AND d.id=m.document_id
                WHERE m.tenant_id=:tenant AND m.document_id IN (:documents) AND m.retrieval_eligible=TRUE
                    AND d.status='ELIGIBLE'
                    AND d.source_content_sha256=v.content_sha256
                    AND c.status='ACTIVE' AND %s AND p.status='ACTIVE' AND %s
                ORDER BY m.document_id,p.id,i.id
                """.formatted(SEARCHABLE_SOURCE, DOCUMENT_READ_SCOPE)).param("tenant", tenant.value()).param("documents", documents).param("actor", actor.value())
                .query((r, _) -> {
                    result.put(r.getObject("document_id", UUID.class), new io.memoryos.objectstorage.StoredObjectReference(
                            new io.memoryos.objectstorage.StoredObjectId(r.getObject("id", UUID.class)),
                            new io.memoryos.objectstorage.ObjectKey(r.getString("object_key")), r.getString("filename"),
                            new io.memoryos.objectstorage.ObjectMetadata(r.getLong("size_bytes"), r.getString("declared_media_type"),
                                    new io.memoryos.objectstorage.ContentSha256(r.getString("content_sha256")))));
                    return true;
                }).list();
        return java.util.Map.copyOf(result);
    }

    /**
     * One keyset page of the Documents the actor may read, each under its first readable mapping. Sources are
     * narrowed by their own read scope first, then every mapping is rechecked with {@link #DOCUMENT_READ_SCOPE}, the
     * rule Search and {@link #readableDocuments} apply, so a browse can never list what a search would refuse.
     */
    public List<SourceDocumentEntry> browse(TenantId tenant, ActorId actor, String indexIdentity, SourceDocumentBrowse browse) {
        var after = browse.after();
        String keyset = after == null ? "" : browse.byName()
                ? "AND (lower(entry.filename), entry.document_id) > (lower(:afterName), :afterId)"
                : "AND (entry.updated_at, entry.document_id) < (:afterTime, :afterId)";
        String order = browse.byName() ? "lower(entry.filename), entry.document_id" : "entry.updated_at DESC, entry.document_id DESC";
        var statement = jdbcClient.sql(BROWSE.formatted(SEARCHABLE_SOURCE, SOURCE_READ_SCOPE, "", DOCUMENT_READ_SCOPE,
                        FileCategorySql.caseExpression("mapped.media_type", "mapped.filename"), """
                        WHERE (:allCategories OR entry.category IN (:categories))
                            AND (:query = '' OR entry.filename ILIKE :pattern OR entry.title ILIKE :pattern) %s
                        ORDER BY %s LIMIT :limit
                        """.formatted(keyset, order), order))
                .param("allSources", browse.sourceIds().isEmpty())
                .param("sources", browse.sourceIds().isEmpty() ? Set.of(new UUID(0, 0)) : browse.sourceIds())
                .param("allCategories", browse.categories().isEmpty())
                .param("categories", browse.categories().isEmpty() ? Set.of("") : browse.categories())
                .param("query", browse.query()).param("pattern", LikePattern.containing(browse.query()))
                .param("limit", browse.limit());
        if (after != null) {
            statement = statement.param("afterTime", after.updatedAt().atOffset(ZoneOffset.UTC))
                    .param("afterName", after.filename()).param("afterId", after.documentId());
        }
        return bindBrowse(statement, tenant, actor, indexIdentity).query(JdbcSourceDocumentRepository::entry).list();
    }

    /** The listed entries of those Documents the actor may read now, as {@link #browse} lists them; any order. */
    public List<SourceDocumentEntry> entries(TenantId tenant, ActorId actor, String indexIdentity, Collection<UUID> documents) {
        if (documents.isEmpty()) return List.of();
        if (documents.size() > 1000) throw new IllegalArgumentException("document batch exceeds 1000");
        var statement = jdbcClient.sql(BROWSE.formatted(SEARCHABLE_SOURCE, SOURCE_READ_SCOPE, "AND m.document_id IN (:documents)",
                        DOCUMENT_READ_SCOPE, FileCategorySql.caseExpression("mapped.media_type", "mapped.filename"), "", "entry.document_id"))
                .param("allSources", true).param("sources", Set.of(new UUID(0, 0)))
                .param("documents", Set.copyOf(documents));
        return bindBrowse(statement, tenant, actor, indexIdentity).query(JdbcSourceDocumentRepository::entry).list();
    }

    private static JdbcClient.StatementSpec bindBrowse(JdbcClient.StatementSpec statement, TenantId tenant, ActorId actor,
                                                       String indexIdentity) {
        return statement.param("tenant", tenant.value()).param("actor", actor.value()).param("identity", indexIdentity);
    }

    private static SourceDocumentEntry entry(ResultSet rs, int row) throws SQLException {
        var groups = rs.getArray("group_names");
        try {
            return new SourceDocumentEntry(rs.getObject("document_id", UUID.class), rs.getObject("generation", UUID.class),
                    rs.getString("filename"), rs.getString("title"), rs.getString("media_type"), rs.getLong("size_bytes"),
                    rs.getString("category"), rs.getTimestamp("updated_at").toInstant(), rs.getObject("source_id", UUID.class),
                    rs.getString("source_name"), SourceType.valueOf(rs.getString("connector_type")), rs.getString("source_url"),
                    SourceAccess.valueOf(rs.getString("access_type")), List.of((String[]) groups.getArray()));
        } finally {
            groups.free();
        }
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
