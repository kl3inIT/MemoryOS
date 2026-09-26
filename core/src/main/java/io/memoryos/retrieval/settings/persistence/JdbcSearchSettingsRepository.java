package io.memoryos.retrieval.settings.persistence;

import io.memoryos.retrieval.settings.EmbeddingProvider;
import io.memoryos.retrieval.settings.SearchGeneration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Embedding providers and search configuration generations; callers own the transaction. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSearchSettingsRepository {
    private final JdbcClient jdbc;

    public JdbcSearchSettingsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Serializes changes to one Tenant's generations, seeding included, for the rest of the transaction. */
    public void lock(UUID tenant) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('memoryos.search-settings:' || :tenant, 0))")
                .param("tenant", tenant.toString()).query((_, _) -> 1).single();
    }

    public Optional<SearchGeneration> present(UUID tenant) { return withStatus(tenant, SearchGeneration.Status.PRESENT); }

    public Optional<SearchGeneration> future(UUID tenant) { return withStatus(tenant, SearchGeneration.Status.FUTURE); }

    private Optional<SearchGeneration> withStatus(UUID tenant, SearchGeneration.Status status) {
        return jdbc.sql("SELECT * FROM search_settings WHERE tenant_id=:tenant AND status=:status")
                .param("tenant", tenant).param("status", status.name()).query((rs, _) -> generation(rs)).optional();
    }

    public Optional<SearchGeneration> generation(UUID tenant, UUID id) {
        return jdbc.sql("SELECT * FROM search_settings WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant).param("id", id).query((rs, _) -> generation(rs)).optional();
    }

    /** Every generation of the Tenant, newest first, with what the administration page shows beside it. */
    public List<GenerationRow> generations(UUID tenant) {
        return jdbc.sql("""
                SELECT s.*, p.name AS provider_name, p.data_boundary AS provider_boundary,
                    (SELECT COUNT(*) FROM document_search_projection r JOIN documents d
                        ON d.tenant_id=r.tenant_id AND d.id=r.document_id AND d.status='ELIGIBLE'
                     WHERE r.index_identity=s.index_identity) AS document_count
                FROM search_settings s JOIN embedding_provider p ON p.tenant_id=s.tenant_id AND p.id=s.provider_id
                WHERE s.tenant_id=:tenant ORDER BY s.created_at DESC, s.id
                """).param("tenant", tenant).query((rs, _) -> new GenerationRow(generation(rs), rs.getString("provider_name"),
                        EmbeddingProvider.DataBoundary.valueOf(rs.getString("provider_boundary")), rs.getLong("document_count"),
                        rs.getTimestamp("cleanup_blocked_at") != null)).list();
    }

    /**
     * Changes whenever another process switches, restores, starts or cancels a rebuild, or edits a provider in use:
     * the IDs and states of the active generations and the revisions of their providers.
     */
    public String version(UUID tenant) {
        return jdbc.sql("""
                SELECT COALESCE(string_agg(s.id::text || ':' || s.status || ':' || p.revision, ',' ORDER BY s.status, s.id), '')
                FROM search_settings s JOIN embedding_provider p ON p.tenant_id=s.tenant_id AND p.id=s.provider_id
                WHERE s.tenant_id=:tenant AND s.status IN ('PRESENT','FUTURE')
                """).param("tenant", tenant).query(String.class).single();
    }

    public Optional<EmbeddingProvider> provider(UUID tenant, UUID id) {
        return jdbc.sql("SELECT * FROM embedding_provider WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant).param("id", id).query((rs, _) -> provider(rs)).optional();
    }

    public List<EmbeddingProvider> providers(UUID tenant) {
        return jdbc.sql("SELECT * FROM embedding_provider WHERE tenant_id=:tenant ORDER BY lower(name), id")
                .param("tenant", tenant).query((rs, _) -> provider(rs)).list();
    }

    /** Providers any generation refers to, PAST ones included: the foreign key keeps them. */
    public Set<UUID> providersInUse(UUID tenant) {
        return Set.copyOf(jdbc.sql("SELECT DISTINCT provider_id FROM search_settings WHERE tenant_id=:tenant")
                .param("tenant", tenant).query(UUID.class).list());
    }

    public boolean nameTaken(UUID tenant, String name, @Nullable UUID except) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM embedding_provider WHERE tenant_id=:tenant AND name=:name AND id<>:except
                """).param("tenant", tenant).param("name", name).param("except", except == null ? new UUID(0, 0) : except)
                .query(Integer.class).single() > 0;
    }

    public void insertProvider(EmbeddingProvider provider) {
        jdbc.sql("""
                INSERT INTO embedding_provider(id,tenant_id,name,endpoint,credential,data_boundary,revision)
                VALUES (:id,:tenant,:name,:endpoint,:credential,:boundary,:revision)
                """).param("id", provider.id()).param("tenant", provider.tenantId()).param("name", provider.name())
                .param("endpoint", provider.endpoint()).param("credential", provider.credential(), Types.VARCHAR)
                .param("boundary", provider.dataBoundary().name()).param("revision", provider.revision()).update();
    }

    /** Writes the provider at {@code provider.revision()} when the stored one is still {@code expected}. */
    public boolean updateProvider(EmbeddingProvider provider, long expected) {
        return jdbc.sql("""
                UPDATE embedding_provider SET name=:name,endpoint=:endpoint,credential=:credential,data_boundary=:boundary,
                    revision=:revision
                WHERE tenant_id=:tenant AND id=:id AND revision=:expected
                """).param("id", provider.id()).param("tenant", provider.tenantId()).param("name", provider.name())
                .param("endpoint", provider.endpoint()).param("credential", provider.credential(), Types.VARCHAR)
                .param("boundary", provider.dataBoundary().name()).param("revision", provider.revision())
                .param("expected", expected).update() == 1;
    }

    public boolean deleteProvider(UUID tenant, UUID id) {
        return jdbc.sql("DELETE FROM embedding_provider WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant).param("id", id).update() == 1;
    }

    public void insertGeneration(SearchGeneration generation) {
        jdbc.sql("""
                INSERT INTO search_settings(id,tenant_id,provider_id,model,dimensions,query_prefix,document_prefix,
                    minimum_semantic_score,chunk_convention,index_identity,status,automatic,created_at,activated_at,retained_until)
                VALUES (:id,:tenant,:provider,:model,:dimensions,:queryPrefix,:documentPrefix,:score,:convention,:identity,
                    :status,:automatic,:created,:activated,:retained)
                """).param("id", generation.id()).param("tenant", generation.tenantId())
                .param("provider", generation.providerId()).param("model", generation.model())
                .param("dimensions", generation.dimensions()).param("queryPrefix", generation.queryPrefix())
                .param("documentPrefix", generation.documentPrefix()).param("score", generation.minimumSemanticScore())
                .param("convention", generation.chunkConvention()).param("identity", generation.identity())
                .param("status", generation.status().name()).param("automatic", generation.automatic())
                .param("created", timestamp(generation.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("activated", timestamp(generation.activatedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("retained", timestamp(generation.retainedUntil()), Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    /** PRESENT or FUTURE to PAST, kept until {@code until}; runs before the replacement becomes PRESENT. */
    public void retire(UUID tenant, UUID id, Instant until) {
        if (jdbc.sql("""
                UPDATE search_settings SET status='PAST',retained_until=:until,cleanup_attempts=0,cleanup_blocked_at=NULL
                WHERE tenant_id=:tenant AND id=:id AND status<>'PAST'
                """).param("tenant", tenant).param("id", id)
                .param("until", timestamp(until), Types.TIMESTAMP_WITH_TIMEZONE).update() != 1) {
            throw new IllegalStateException("search generation changed concurrently");
        }
    }

    /** FUTURE or PAST to PRESENT; the previous PRESENT must already be PAST (one PRESENT per Tenant). */
    public void activate(UUID tenant, UUID id, Instant at) {
        if (jdbc.sql("""
                UPDATE search_settings SET status='PRESENT',activated_at=:at,retained_until=NULL,cleanup_attempts=0,
                    cleanup_blocked_at=NULL
                WHERE tenant_id=:tenant AND id=:id AND status<>'PRESENT'
                """).param("tenant", tenant).param("id", id)
                .param("at", timestamp(at), Types.TIMESTAMP_WITH_TIMEZONE).update() != 1) {
            throw new IllegalStateException("search generation changed concurrently");
        }
    }

    /** PAST generations whose retention ended, oldest first; their indexes are deleted. */
    public List<SearchGeneration> expired(UUID tenant, Instant now) {
        return jdbc.sql("""
                SELECT * FROM search_settings WHERE tenant_id=:tenant AND status='PAST' AND retained_until<=:now
                ORDER BY retained_until, id
                """).param("tenant", tenant).param("now", timestamp(now), Types.TIMESTAMP_WITH_TIMEZONE)
                .query((rs, _) -> generation(rs)).list();
    }

    /** Counts a failed index deletion; returns the attempts so far and marks the generation blocked from {@code blockAt}. */
    public int cleanupFailed(UUID tenant, UUID id, int blockAt) {
        return jdbc.sql("""
                UPDATE search_settings SET cleanup_attempts=cleanup_attempts+1,
                    cleanup_blocked_at=CASE WHEN cleanup_attempts+1>=:blockAt THEN COALESCE(cleanup_blocked_at,CURRENT_TIMESTAMP)
                        ELSE cleanup_blocked_at END
                WHERE tenant_id=:tenant AND id=:id AND status='PAST'
                RETURNING cleanup_attempts
                """).param("tenant", tenant).param("id", id).param("blockAt", blockAt).query(Integer.class).optional().orElse(0);
    }

    /**
     * Removes a PAST generation whose index is gone, with its readiness rows and any work still queued for that
     * index. The work table belongs to ingestion; cancelling it here keeps removal one transaction.
     */
    public boolean deleteGeneration(SearchGeneration generation) {
        cancelWork(generation.identity());
        jdbc.sql("DELETE FROM document_search_projection WHERE index_identity=:identity")
                .param("identity", generation.identity()).update();
        return jdbc.sql("DELETE FROM search_settings WHERE tenant_id=:tenant AND id=:id AND status='PAST'")
                .param("tenant", generation.tenantId()).param("id", generation.id()).update() == 1;
    }

    /** Cancels the work queued or running for an index that stops being written. */
    public void cancelWork(String identity) {
        jdbc.sql("""
                UPDATE search_index_operations SET status='CANCELLED',claim_token=NULL,lease_expires_at=NULL,
                    completed_at=CURRENT_TIMESTAMP,error_code='SEARCH_OBSOLETE',dispatch_token=NULL,dispatch_lease_expires_at=NULL
                WHERE index_identity=:identity AND status IN ('NOT_STARTED','IN_PROGRESS')
                """).param("identity", identity).update();
    }

    /**
     * Rebuild progress of {@code future} over the documents that search can serve: eligible, extracted, of an active
     * Tenant. A document is ready when the index holds its current content generation, failed when the index work for
     * that generation failed, and pending otherwise. {@code uncovered} counts the documents PRESENT serves that are not
     * ready in the rebuilt index: switching would drop them from search, whatever the totals say.
     */
    public Progress progress(String future, String present) {
        return jdbc.sql("""
                SELECT COUNT(*) AS total,
                    COUNT(*) FILTER (WHERE f.generation=d.content_generation) AS ready,
                    COUNT(*) FILTER (WHERE f.generation IS DISTINCT FROM d.content_generation AND EXISTS (
                        SELECT 1 FROM search_index_operations w WHERE w.tenant_id=d.tenant_id AND w.document_id=d.id
                            AND w.generation=d.content_generation AND w.action='INDEX' AND w.index_identity=:future
                            AND w.status='FAILED')) AS failed,
                    COUNT(*) FILTER (WHERE p.generation IS NOT NULL
                        AND f.generation IS DISTINCT FROM d.content_generation) AS uncovered
                FROM documents d JOIN tenants t ON t.id=d.tenant_id
                LEFT JOIN document_search_projection f ON f.tenant_id=d.tenant_id AND f.document_id=d.id AND f.index_identity=:future
                LEFT JOIN document_search_projection p ON p.tenant_id=d.tenant_id AND p.document_id=d.id AND p.index_identity=:present
                WHERE d.status='ELIGIBLE' AND t.status='ACTIVE' AND d.extraction_artifact_id IS NOT NULL
                """).param("future", future).param("present", present)
                .query((rs, _) -> new Progress(rs.getLong("ready"), rs.getLong("total"), rs.getLong("failed"),
                        rs.getLong("uncovered"))).single();
    }

    public record GenerationRow(SearchGeneration generation, String providerName, EmbeddingProvider.DataBoundary dataBoundary,
            long documentCount, boolean cleanupBlocked) { }

    /** Documents ready in the rebuilt index, all servable documents, failed ones, and PRESENT's not ready there. */
    public record Progress(long ready, long total, long failed, long uncovered) {
        public long pending() { return Math.max(0, total - ready - failed); }
    }

    private static @Nullable OffsetDateTime timestamp(@Nullable Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static SearchGeneration generation(ResultSet rs) throws SQLException {
        return new SearchGeneration(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("provider_id", UUID.class), rs.getString("model"), rs.getInt("dimensions"),
                rs.getString("query_prefix"), rs.getString("document_prefix"), rs.getDouble("minimum_semantic_score"),
                rs.getString("chunk_convention"), rs.getString("index_identity"),
                SearchGeneration.Status.valueOf(rs.getString("status")), rs.getBoolean("automatic"),
                rs.getTimestamp("created_at").toInstant(), instant(rs, "activated_at"), instant(rs, "retained_until"));
    }

    private static EmbeddingProvider provider(ResultSet rs) throws SQLException {
        return new EmbeddingProvider(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("name"), rs.getString("endpoint"), rs.getString("credential"),
                EmbeddingProvider.DataBoundary.valueOf(rs.getString("data_boundary")), rs.getLong("revision"));
    }
}
