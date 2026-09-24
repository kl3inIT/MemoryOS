package io.memoryos.retrieval.settings.persistence;

import io.memoryos.retrieval.settings.EmbeddingProvider;
import io.memoryos.retrieval.settings.SearchGeneration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Embedding providers and search configuration generations; callers own the transaction. */
@Repository
public class JdbcSearchSettingsRepository {
    private final JdbcClient jdbc;

    public JdbcSearchSettingsRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Serializes changes to one Tenant's generations, seeding included, for the rest of the transaction. */
    public void lock(UUID tenant) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('memoryos.search-settings:' || :tenant, 0))")
                .param("tenant", tenant.toString()).query((rs, _) -> 1).single();
    }

    public Optional<SearchGeneration> present(UUID tenant) {
        return jdbc.sql("SELECT * FROM search_settings WHERE tenant_id=:tenant AND status='PRESENT'")
                .param("tenant", tenant).query((rs, _) -> generation(rs)).optional();
    }

    public Optional<EmbeddingProvider> provider(UUID tenant, UUID id) {
        return jdbc.sql("SELECT * FROM embedding_provider WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant).param("id", id).query((rs, _) -> provider(rs)).optional();
    }

    public void insertProvider(EmbeddingProvider provider) {
        jdbc.sql("""
                INSERT INTO embedding_provider(id,tenant_id,name,endpoint,credential,data_boundary,revision)
                VALUES (:id,:tenant,:name,:endpoint,:credential,:boundary,:revision)
                """).param("id", provider.id()).param("tenant", provider.tenantId()).param("name", provider.name())
                .param("endpoint", provider.endpoint()).param("credential", provider.credential(), Types.VARCHAR)
                .param("boundary", provider.dataBoundary().name()).param("revision", provider.revision()).update();
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
