package io.memoryos.document.persistence;

import io.memoryos.document.DocumentChunk;
import io.memoryos.document.DocumentChunkSet;
import io.memoryos.document.DocumentId;
import io.memoryos.document.DocumentIndexState;
import io.memoryos.iam.tenant.TenantId;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcDocumentChunkRepository {
    private static final int INSERT_BATCH_SIZE = 128;
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JdbcDocumentChunkRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Optional<ArtifactReader> openReader(TenantId tenant, DocumentId document, UUID generation) {
        var reader = jdbc.sql("""
                SELECT d.title,d.media_type,d.updated_at,a.id,a.object_key,a.content_sha256,a.size_bytes
                FROM documents d JOIN document_extraction_artifacts a
                    ON a.tenant_id=d.tenant_id AND a.id=d.extraction_artifact_id
                WHERE d.tenant_id=:tenant AND d.id=:document AND d.content_generation=:generation
                    AND d.status='ELIGIBLE' AND a.state='ACTIVE'
                FOR SHARE OF d,a
                """).param("tenant", tenant.value()).param("document", document.value()).param("generation", generation)
                .query((rs, _) -> new ArtifactReader(tenant, document, generation, UUID.randomUUID(),
                        rs.getObject("id", UUID.class), rs.getString("object_key"), rs.getString("content_sha256"),
                        rs.getLong("size_bytes"), rs.getString("title"), rs.getString("media_type"),
                        rs.getTimestamp("updated_at").toInstant())).optional();
        reader.ifPresent(r -> jdbc.sql("""
                INSERT INTO document_artifact_readers(tenant_id,artifact_id,reader_id,expires_at)
                VALUES (:tenant,:artifact,:reader,CURRENT_TIMESTAMP + INTERVAL '2' MINUTE)
                """).param("tenant", tenant.value()).param("artifact", r.artifactId()).param("reader", r.readerId()).update());
        return reader;
    }

    public void closeReader(UUID reader) {
        jdbc.sql("DELETE FROM document_artifact_readers WHERE reader_id=:reader").param("reader", reader).update();
    }

    @Transactional
    public boolean publish(ArtifactReader reader, List<DocumentChunk> chunks) {
        boolean current = jdbc.sql("""
                SELECT id FROM documents WHERE tenant_id=:tenant AND id=:document
                    AND content_generation=:generation AND status='ELIGIBLE' FOR UPDATE
                """).param("tenant", reader.tenantId().value()).param("document", reader.documentId().value())
                .param("generation", reader.generation()).query(UUID.class).optional().isPresent();
        if (!current) return false;
        jdbc.sql("DELETE FROM document_chunks WHERE tenant_id=:tenant AND document_id=:document")
                .param("tenant", reader.tenantId().value()).param("document", reader.documentId().value()).update();
        for (int offset = 0; offset < chunks.size(); offset += INSERT_BATCH_SIZE) {
            insertChunks(reader, chunks.subList(offset, Math.min(offset + INSERT_BATCH_SIZE, chunks.size())));
        }
        jdbc.sql("UPDATE documents SET chunk_generation=:generation,chunk_count=:count,chunk_convention=:convention WHERE tenant_id=:tenant AND id=:document")
                .param("convention", DocumentChunk.CONVENTION)
                .param("generation", reader.generation()).param("count", chunks.size())
                .param("tenant", reader.tenantId().value()).param("document", reader.documentId().value()).update();
        return true;
    }

    private void insertChunks(ArtifactReader reader, List<DocumentChunk> chunks) {
        // Only fixed placeholders enter SQL; all document text and metadata are bound parameters.
        String values = String.join(",", Collections.nCopies(chunks.size(), "(?,?,?,?,?,?,?,?,?,?,?)"));
        List<Object> parameters = new ArrayList<>(chunks.size() * 11);
        for (DocumentChunk chunk : chunks) {
            Collections.addAll(parameters, reader.tenantId().value(), reader.documentId().value(),
                    reader.generation(), chunk.ordinal(), chunk.content(), mapper.writeValueAsString(chunk.headings()),
                    chunk.blockIndex(), chunk.part(), chunk.provenanceJson(), chunk.contentSha256(), chunk.tokenCount());
        }
        int inserted = jdbc.sql("""
                INSERT INTO document_chunks(tenant_id,document_id,generation,ordinal,content,headings_json,
                    block_index,part,provenance_json,content_sha256,token_count) VALUES
                """ + values).params(parameters).update();
        if (inserted != chunks.size()) throw new IllegalStateException("incomplete chunk publication");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<DocumentChunkSet> load(TenantId tenant, DocumentId document, UUID generation) {
        var header = jdbc.sql("""
                SELECT title,media_type,updated_at,chunk_count,metadata_json::jsonb ->> 'user_file_id' AS user_file_id FROM documents
                WHERE tenant_id=:tenant AND id=:document AND content_generation=:generation
                    AND chunk_generation=:generation AND chunk_count>0 AND status='ELIGIBLE' AND chunk_convention=:convention
                """).param("tenant", tenant.value()).param("document", document.value()).param("generation", generation)
                .param("convention", DocumentChunk.CONVENTION)
                .query((rs, _) -> new Header(rs.getString("title"), rs.getString("media_type"),
                        rs.getTimestamp("updated_at").toInstant(), rs.getInt("chunk_count"), rs.getString("user_file_id"))).optional();
        if (header.isEmpty()) return Optional.empty();
        List<DocumentChunk> chunks = jdbc.sql("""
                SELECT * FROM document_chunks WHERE tenant_id=:tenant AND document_id=:document AND generation=:generation
                ORDER BY ordinal
                """).param("tenant", tenant.value()).param("document", document.value()).param("generation", generation)
                .query((rs, _) -> new DocumentChunk(rs.getInt("ordinal"), rs.getString("content"),
                        mapper.readValue(rs.getString("headings_json"), mapper.getTypeFactory().constructCollectionType(List.class, String.class)),
                        rs.getInt("block_index"), rs.getInt("part"), rs.getString("provenance_json"),
                        rs.getString("content_sha256"), rs.getInt("token_count"))).list();
        var h = header.orElseThrow();
        if (h.count() != chunks.size()) throw new IllegalStateException("incomplete current chunks");
        return Optional.of(new DocumentChunkSet(tenant, document, generation, h.title(), h.mediaType(), h.updatedAt(), chunks,
                h.userFileId() == null ? null : UUID.fromString(h.userFileId())));
    }

    /**
     * An index is served unless it belongs to a search generation that is not PRESENT (the FUTURE being rebuilt or a
     * retained PAST one). Before the first generation is seeded the only index is the served one.
     */
    private static final String SERVED = """
            NOT EXISTS (SELECT 1 FROM search_settings s WHERE s.index_identity=:identity AND s.status<>'PRESENT')
            """;

    /**
     * Records the generation as complete in the index with the chunk count written there. {@code
     * document_search_projection} holds readiness per index, so a document can be ready in one generation's index
     * while another is being built. The columns on {@code documents} keep the served generation that Source status
     * and retention read, so only the served index writes them.
     */
    public boolean markReady(TenantId tenant, DocumentId document, UUID generation, String identity) {
        var count = jdbc.sql("""
                SELECT chunk_count FROM documents
                WHERE tenant_id=:tenant AND id=:document AND content_generation=:generation
                    AND chunk_generation=:generation AND chunk_count>0 AND status='ELIGIBLE'
                FOR UPDATE
                """).param("tenant", tenant.value()).param("document", document.value()).param("generation", generation)
                .query(Integer.class).optional();
        if (count.isEmpty()) return false;
        jdbc.sql("""
                UPDATE documents SET searchable_generation=:generation,search_index_identity=:identity,search_error_code=NULL
                WHERE tenant_id=:tenant AND id=:document AND """ + " " + SERVED)
                .param("tenant", tenant.value()).param("document", document.value()).param("generation", generation)
                .param("identity", identity).update();
        jdbc.sql("""
                INSERT INTO document_search_projection(tenant_id,document_id,index_identity,generation,chunk_count)
                VALUES (:tenant,:document,:identity,:generation,:count)
                ON CONFLICT (tenant_id,document_id,index_identity)
                DO UPDATE SET generation=EXCLUDED.generation,chunk_count=EXCLUDED.chunk_count,ready_at=CURRENT_TIMESTAMP
                """).param("tenant", tenant.value()).param("document", document.value()).param("generation", generation)
                .param("identity", identity).param("count", count.orElseThrow()).update();
        return true;
    }

    /**
     * Points every document's served generation at the one ready in the index, or at none when the index does not
     * hold the document; runs when the index's generation becomes PRESENT.
     */
    public void serve(String identity) {
        jdbc.sql("""
                UPDATE documents d SET searchable_generation=p.generation,search_index_identity=p.index_identity,search_error_code=NULL
                FROM document_search_projection p
                WHERE p.tenant_id=d.tenant_id AND p.document_id=d.id AND p.index_identity=:identity
                    AND (d.searchable_generation IS DISTINCT FROM p.generation OR d.search_index_identity IS DISTINCT FROM p.index_identity
                        OR d.search_error_code IS NOT NULL AND p.generation=d.content_generation)
                """).param("identity", identity).update();
        jdbc.sql("""
                UPDATE documents d SET searchable_generation=NULL,search_index_identity=NULL
                WHERE d.searchable_generation IS NOT NULL AND NOT EXISTS (SELECT 1 FROM document_search_projection p
                    WHERE p.tenant_id=d.tenant_id AND p.document_id=d.id AND p.index_identity=:identity)
                """).param("identity", identity).update();
    }

    /**
     * Records the state of the current content generation in one index. Readiness is withdrawn only when that
     * generation is the one ready there (projection repair); a pending or failed replacement leaves the previous
     * ready generation searchable. Only the served index writes the document's columns: a rebuild beside it neither
     * hides the document nor reports its failures on the Source.
     */
    public void searchState(TenantId tenant, DocumentId document, UUID generation, String error, String identity) {
        jdbc.sql("""
                UPDATE documents SET search_error_code=:error,
                    searchable_generation=CASE WHEN searchable_generation=:generation THEN NULL ELSE searchable_generation END,
                    search_index_identity=CASE WHEN searchable_generation=:generation THEN NULL ELSE search_index_identity END
                WHERE tenant_id=:tenant AND id=:document AND content_generation=:generation AND """ + " " + SERVED)
                .param("tenant", tenant.value()).param("document", document.value()).param("generation", generation)
                .param("error", error, Types.VARCHAR).param("identity", identity).update();
        jdbc.sql("""
                DELETE FROM document_search_projection p USING documents d
                WHERE p.tenant_id=:tenant AND p.document_id=:document AND p.generation=:generation AND p.index_identity=:identity
                    AND d.tenant_id=p.tenant_id AND d.id=p.document_id AND d.content_generation=:generation
                """).param("tenant", tenant.value()).param("document", document.value()).param("generation", generation)
                .param("identity", identity).update();
    }

    /** Whether the generation is the one currently served under the index identity. */
    public boolean isCurrent(TenantId tenant, DocumentId document, UUID generation, String identity) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM documents d JOIN document_search_projection p
                    ON p.tenant_id=d.tenant_id AND p.document_id=d.id AND p.index_identity=:identity
                WHERE d.tenant_id=:tenant AND d.id=:document AND d.status='ELIGIBLE' AND p.generation=:generation
                """).param("tenant", tenant.value()).param("document", document.value()).param("generation", generation)
                .param("identity", identity).query(Integer.class).single() == 1;
    }

    public List<DocumentIndexState> scan(String identity, String after, int limit) {
        return jdbc.sql("""
                SELECT d.tenant_id,d.id,d.content_generation,
                    COALESCE(CASE WHEN p.generation=d.content_generation THEN p.chunk_count END,d.chunk_count,0) AS chunk_count,
                    COALESCE(p.generation=d.content_generation,FALSE) AS ready
                FROM documents d JOIN tenants t ON t.id=d.tenant_id
                LEFT JOIN document_search_projection p ON p.tenant_id=d.tenant_id AND p.document_id=d.id AND p.index_identity=:identity
                WHERE d.status='ELIGIBLE' AND t.status='ACTIVE' AND d.extraction_artifact_id IS NOT NULL
                    AND d.tenant_id::text || ':' || d.id::text > :after
                ORDER BY d.tenant_id::text || ':' || d.id::text LIMIT :limit
                """).param("identity", identity).param("after", after).param("limit", Math.clamp(limit, 1, 100))
                .query((rs, _) -> new DocumentIndexState(new TenantId(rs.getObject("tenant_id", UUID.class)),
                        new DocumentId(rs.getObject("id", UUID.class)), rs.getObject("content_generation", UUID.class),
                        rs.getInt("chunk_count"), rs.getBoolean("ready"))).list();
    }

    public Map<UUID, UUID> currentGenerations(TenantId tenant, List<UUID> documents, String readyIdentity) {
        if (documents.isEmpty()) return Map.of();
        if (documents.size() > 1000) throw new IllegalArgumentException("document batch exceeds 1000");
        var rows = jdbc.sql("""
                SELECT d.id,CASE WHEN :identity='' THEN d.content_generation ELSE p.generation END AS generation
                FROM documents d LEFT JOIN document_search_projection p
                    ON p.tenant_id=d.tenant_id AND p.document_id=d.id AND p.index_identity=:identity
                WHERE d.tenant_id=:tenant AND d.id IN (:documents)
                    AND d.status='ELIGIBLE' AND (:identity='' OR p.generation IS NOT NULL)
                """).param("tenant", tenant.value()).param("documents", documents).param("identity", readyIdentity)
                .query((rs, _) -> Map.entry(rs.getObject("id", UUID.class), rs.getObject("generation", UUID.class))).list();
        return rows.stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Map<UUID, Set<UUID>> retainedGenerations(TenantId tenant, List<UUID> documents) {
        if (documents.isEmpty()) return Map.of();
        if (documents.size() > 1000) throw new IllegalArgumentException("document batch exceeds 1000");
        var rows = jdbc.sql("""
                SELECT id,content_generation,searchable_generation FROM documents
                WHERE tenant_id=:tenant AND id IN (:documents) AND status='ELIGIBLE'
                """).param("tenant", tenant.value()).param("documents", documents)
                .query((rs, _) -> {
                    var served = rs.getObject("searchable_generation", UUID.class);
                    var current = rs.getObject("content_generation", UUID.class);
                    return Map.entry(rs.getObject("id", UUID.class), served == null || served.equals(current) ? Set.of(current) : Set.of(current, served));
                }).list();
        return rows.stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public record ArtifactReader(TenantId tenantId, DocumentId documentId, UUID generation, UUID readerId,
            UUID artifactId, String objectKey, String hash, long size, String title, String mediaType, Instant updatedAt) { }
    private record Header(String title, String mediaType, Instant updatedAt, int count, @org.jspecify.annotations.Nullable String userFileId) { }
}
