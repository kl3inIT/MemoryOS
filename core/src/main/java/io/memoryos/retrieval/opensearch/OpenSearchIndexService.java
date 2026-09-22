package io.memoryos.retrieval.opensearch;

import io.memoryos.iam.identity.ActorId;
import io.memoryos.document.DocumentChunk;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.DocumentAccess;
import io.memoryos.connector.DocumentSourceMetadata;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentChunkSet;
import io.memoryos.document.DocumentId;
import io.memoryos.document.DocumentIndexState;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.retrieval.SearchHit;
import io.memoryos.retrieval.SearchDocument;
import io.memoryos.retrieval.SearchDocumentUnavailableException;
import io.memoryos.retrieval.SearchPage;
import io.memoryos.retrieval.SearchRequestException;
import io.memoryos.retrieval.SearchIndex;
import io.memoryos.retrieval.SearchUnavailableException;
import io.memoryos.retrieval.SearchFilters;
import io.memoryos.retrieval.SearchQuery;
import io.memoryos.retrieval.SearchTasks;
import io.memoryos.retrieval.SearchTimings;
import io.memoryos.retrieval.embedding.ValidatedEmbeddingService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OpenSearchIndexService implements SearchIndex {
    private final OpenSearchGateway gateway;
    private final ValidatedEmbeddingService embeddings;
    private final SearchProperties properties;
    private final ObjectMapper mapper;
    private final String identity;
    private final DocumentChunkPort documents;
    private final SourceSearchService sourceSearch;
    private final SearchTimings timings;
    private static final int ACCESS_UPDATE_BATCH = 128;
    private String sweepCursor = "";

    public OpenSearchIndexService(OpenSearchGateway gateway, ValidatedEmbeddingService embeddings,
            SearchProperties properties, ObjectMapper mapper, DocumentChunkPort documents, SourceSearchService sourceSearch, SearchTimings timings) {
        this.gateway = gateway; this.embeddings = embeddings; this.properties = properties; this.mapper = mapper;
        this.documents = documents;
        this.sourceSearch = sourceSearch;
        this.timings = timings;
        try {
            String profile = properties.embeddingEndpoint() + ":" + properties.model() + ":" + properties.dimensions() + ":" + DocumentChunk.CONVENTION;
            this.identity = properties.indexPrefix() + "-" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(profile.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    @Override public String identity() { return identity; }
    public int candidateLimit() { return properties.candidateLimit(); }
    private String readAlias() { return identity + "-read"; }
    private String pipeline() { return identity + "-hybrid"; }

    public synchronized void ensureIndex() {
        if (!gateway.exists("/" + identity)) {
            var fields = new HashMap<String, Object>();
            for (String field : List.of("tenant_id", "document_id", "generation", "content_hash", "chunk_key", "media_type", "index_identity")) {
                fields.put(field, Map.of("type", "keyword"));
            }
            fields.put("title", textMapping());
            fields.put("content", textMapping());
            fields.put("ordinal", Map.of("type", "integer"));
            fields.put("updated_at", Map.of("type", "date"));
            fields.put("provenance", Map.of("type", "text", "index", false));
            fields.put("vector", Map.of("type", "knn_vector", "dimension", properties.dimensions(),
                    "method", Map.of("name", "hnsw", "engine", "faiss", "space_type", "cosinesimil",
                            "parameters", Map.of("ef_construction", 256, "m", 32))));
            gateway.json("PUT", "/" + identity, Map.of(), Map.of(
                    "settings", Map.of("index.knn", true, "number_of_shards", 1, "number_of_replicas", properties.replicas(),
                            "analysis", Map.of("analyzer", Map.of("folded", Map.of("tokenizer", "standard", "filter", List.of("lowercase", "asciifolding"))))),
                    "mappings", Map.of("dynamic", "strict", "_meta", Map.of("identity", identity, "model", properties.model()), "properties", fields),
                    "aliases", Map.of(readAlias(), Map.of())));
        }
        var mapping = gateway.json("GET", "/" + identity + "/_mapping", Map.of(), null).path(identity).path("mappings");
        if (!identity.equals(mapping.path("_meta").path("identity").asString())
                || !properties.model().equals(mapping.path("_meta").path("model").asString())
                || mapping.path("properties").path("vector").path("dimension").asInt() != properties.dimensions()) {
            throw new SearchUnavailableException();
        }
        // Additive mapping keeps the vector identity and all reusable embeddings intact.
        if (!mapping.path("properties").has("user_file_id")) gateway.json("PUT", "/" + identity + "/_mapping", Map.of(),
                Map.of("properties", Map.of("user_file_id", Map.of("type", "keyword"))));
        if (!mapping.path("properties").has("source_metadata")) gateway.json("PUT", "/" + identity + "/_mapping", Map.of(),
                Map.of("properties", Map.of("metadata_hash", Map.of("type", "keyword"), "source_metadata", Map.of(
                        "type", "nested", "properties", Map.of("source_id", Map.of("type", "keyword"),
                                "item_id", Map.of("type", "keyword"), "type", Map.of("type", "keyword"),
                                "created_at", Map.of("type", "date"), "updated_at", Map.of("type", "date"),
                                "authors", Map.of("type", "text", "index", false))))));
        if (!mapping.path("properties").has("access_control_list")) gateway.json("PUT", "/" + identity + "/_mapping", Map.of(),
                Map.of("properties", Map.of("access_public", Map.of("type", "boolean"), "access_control_list", Map.of("type", "keyword"))));
        if (!gateway.exists("/" + readAlias())) {
            gateway.json("PUT", "/" + identity + "/_alias/" + readAlias(), Map.of(), Map.of());
        }
        var aliases = gateway.json("GET", "/" + readAlias() + "/_alias/" + readAlias(), Map.of(), null);
        if (aliases.size() != 1 || !aliases.has(identity)) throw new SearchUnavailableException();
        gateway.json("PUT", "/_search/pipeline/" + pipeline(), Map.of(), Map.of("phase_results_processors", List.of(
                Map.of("normalization-processor", Map.of("normalization", Map.of("technique", "min_max"),
                        "combination", Map.of("technique", "arithmetic_mean", "parameters",
                                Map.of("weights", List.of(properties.keywordWeight(), 1 - properties.keywordWeight()))))))));
    }

    private static Map<String,Object> textMapping() {
        return Map.of("type", "text", "fields", Map.of("folded", Map.of("type", "text", "analyzer", "folded")));
    }

    @Override
    public void index(DocumentChunkSet document) {
        ensureIndex();
        var origins = sourceSearch.indexMetadata(document.tenantId(), document.documentId(), document.generation());
        var sourceMetadata = metadata(origins);
        var access = sourceSearch.indexAccess(document.tenantId(), document.documentId());
        String metadataHash = metadataHash(origins, access);
        for (int offset = 0; offset < document.chunks().size(); offset += embeddings.batchSize()) {
            var batch = document.chunks().subList(offset, Math.min(offset + embeddings.batchSize(), document.chunks().size()));
            var found = existing(document, batch);
            var missing = batch.stream().filter(chunk -> !found.containsKey(chunk.contentSha256())).toList();
            if (!missing.isEmpty()) {
                var generated = embeddings.batch(missing.stream().map(DocumentChunk::content).toList(),
                        new ValidatedEmbeddingService.Caller(document.tenantId().value(), null, io.memoryos.usage.AiUsageFlow.EMBEDDING_INDEXING));
                for (int index = 0; index < missing.size(); index++) found.put(missing.get(index).contentSha256(), generated.get(index));
            }
            var body = new StringBuilder();
            for (var chunk : batch) {
                body.append(mapper.writeValueAsString(Map.of("index", Map.of("_id", document.chunkId(chunk.ordinal()))))).append('\n');
                var source = new HashMap<String,Object>();
                source.put("tenant_id", document.tenantId().value().toString());
                source.put("document_id", document.documentId().value().toString());
                if (document.userFileId() != null) source.put("user_file_id", document.userFileId().toString());
                source.put("generation", document.generation().toString());
                source.put("chunk_key", document.chunkId(chunk.ordinal()));
                source.put("ordinal", chunk.ordinal());
                source.put("title", document.title());
                source.put("media_type", document.mediaType());
                source.put("content", chunk.content());
                source.put("provenance", chunk.provenanceJson());
                source.put("updated_at", document.updatedAt().toString());
                source.put("source_metadata", sourceMetadata);
                source.put("metadata_hash", metadataHash);
                source.put("access_public", access.everyone());
                source.put("access_control_list", access.sortedTokens());
                source.put("content_hash", chunk.contentSha256());
                source.put("index_identity", identity);
                source.put("vector", found.get(chunk.contentSha256()));
                body.append(mapper.writeValueAsString(source)).append('\n');
            }
            var response = gateway.bulk("/" + identity + "/_bulk", body.toString());
            if (response.path("errors").asBoolean(true) || response.path("items").size() != batch.size()) throw new SearchUnavailableException();
            int acknowledged = 0;
            for (JsonNode item : response.path("items")) {
                int status = item.path("index").path("status").asInt();
                if (status < 200 || status >= 300 || !document.chunkId(batch.get(acknowledged++).ordinal())
                        .equals(item.path("index").path("_id").asString())) throw new SearchUnavailableException();
            }
        }
        var count = gateway.json("POST", "/" + readAlias() + "/_count", Map.of(),
                Map.of("query", Map.of("bool", Map.of("filter", List.of(
                        term("tenant_id", document.tenantId().value().toString()), term("document_id", document.documentId().value().toString()),
                        term("generation", document.generation().toString()), term("index_identity", identity))))));
        if (count.path("count").asInt(-1) != document.chunks().size()) throw new SearchUnavailableException();
    }

    private Map<String,float[]> existing(DocumentChunkSet document, List<DocumentChunk> chunks) {
        // Surviving vectors in this same model space also cover metadata-only changes.
        var response = gateway.json("POST", "/" + identity + "/_search", Map.of(), Map.of(
                "size", chunks.size(), "_source", List.of("content_hash", "vector"),
                "collapse", Map.of("field", "content_hash"),
                "query", Map.of("bool", Map.of("filter", List.of(term("tenant_id", document.tenantId().value().toString()),
                        term("document_id", document.documentId().value().toString()), term("index_identity", identity),
                        Map.of("terms", Map.of("content_hash", chunks.stream().map(DocumentChunk::contentSha256).distinct().toList())))))));
        var result = new HashMap<String,float[]>();
        for (var hit : response.path("hits").path("hits")) {
            var source = hit.path("_source");
            var raw = source.path("vector");
            if (raw.size() != properties.dimensions()) continue;
            float[] vector = new float[raw.size()];
            boolean valid = true;
            double norm = 0;
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) raw.get(i).asDouble(Double.NaN);
                valid &= Float.isFinite(vector[i]);
                norm += (double) vector[i] * vector[i];
            }
            if (valid && norm > 0) result.put(source.path("content_hash").asString(), vector);
        }
        return result;
    }

    public List<SearchHit> search(TenantId tenant, String query, List<String> mediaTypes, Instant since, Collection<String> accessTokens) {
        return search(tenant, null, query, mediaTypes, since, accessTokens);
    }

    /** As above; a known actor's query embedding is added to the AI usage ledger. */
    public List<SearchHit> search(TenantId tenant, @Nullable ActorId actor, String query, List<String> mediaTypes, Instant since,
                                  Collection<String> accessTokens) {
        if (!gateway.exists("/" + readAlias())) return List.of();
        return searchPrepared(tenant, query, embeddings.query(query, queryCaller(tenant, actor)), mediaTypes, since, SearchFilters.NONE, List.of(), accessTokens);
    }

    /** Search a pre-authorized Source scope; an empty scope intentionally produces no indexed results. */
    public List<SearchHit> search(SourceSearchScope scope, String query, List<String> mediaTypes, Instant since) {
        if (scope.sources().isEmpty() || !gateway.exists("/" + readAlias())) return List.of();
        return searchPrepared(scope.tenant(), query, embeddings.query(query), mediaTypes, since, SearchFilters.NONE,
                scope.sources().keySet().stream().map(UUID::toString).toList(), scope.accessTokens());
    }

    /** Resolve the alias and embed each distinct text once for this Search call. */
    public List<List<SearchHit>> batch(SourceSearchScope scope, List<SearchQuery> queries, SearchFilters filters, Runnable checkActive) {
        if (queries.isEmpty() || queries.size() > 8) throw new SearchRequestException();
        checkActive.run();
        if (!gateway.exists("/" + readAlias())) return queries.stream().map(_ -> List.<SearchHit>of()).toList();
        var texts = queries.stream().map(SearchQuery::text).distinct().toList();
        var vectors = new LinkedHashMap<String, float[]>();
        for (int offset = 0; offset < texts.size(); offset += embeddings.batchSize()) {
            checkActive.run();
            var inputs = texts.subList(offset, Math.min(offset + embeddings.batchSize(), texts.size()));
            var output = timings.measure(SearchTimings.Stage.EMBEDDING, () -> embeddings.batch(inputs, queryCaller(scope.tenant(), scope.actor())));
            for (int i = 0; i < inputs.size(); i++) vectors.put(inputs.get(i), output.get(i));
        }
        List<Callable<List<SearchHit>>> tasks = texts.stream().<Callable<List<SearchHit>>>map(text ->
                () -> timings.measure(SearchTimings.Stage.HYBRID, () -> searchPrepared(scope.tenant(), text, vectors.get(text), List.of(), null, filters,
                        scope.sources().keySet().stream().map(UUID::toString).toList(), scope.accessTokens()))).toList();
        var results = SearchTasks.run(tasks, checkActive);
        // The adapter uses the same hybrid request for both groups. Reuse identical IO but retain
        // each group's rank list and weight for fusion.
        return queries.stream().map(query -> results.get(texts.indexOf(query.text()))).toList();
    }

    private List<SearchHit> searchPrepared(TenantId tenant, String query, float[] vector,
            List<String> mediaTypes, Instant since, SearchFilters restrictions, List<String> sourceIds, Collection<String> accessTokens) {
        return searchPrepared(tenant, query, vector, mediaTypes, since, restrictions, sourceIds, List.of(), accessTokens);
    }

    private static ValidatedEmbeddingService.@Nullable Caller queryCaller(TenantId tenant, @Nullable ActorId actor) {
        return actor == null ? null : new ValidatedEmbeddingService.Caller(tenant.value(), actor.value(), io.memoryos.usage.AiUsageFlow.EMBEDDING_QUERY);
    }

    public List<SearchHit> searchFiles(TenantId tenant, String query, Map<UUID, UUID> generations, Map<UUID, UUID> files) {
        return searchFiles(tenant, null, query, generations, files);
    }

    public List<SearchHit> searchFiles(TenantId tenant, @Nullable ActorId actor, String query, Map<UUID, UUID> generations, Map<UUID, UUID> files) {
        if (generations.isEmpty() || files.isEmpty() || !gateway.exists("/" + readAlias())) return List.of();
        List<Object> allowed = new ArrayList<>();
        files.forEach((file, document) -> {
            var generation = generations.get(document);
            if (generation != null) allowed.add(Map.of("bool", Map.of("filter", List.of(term("user_file_id", file.toString()),
                    term("document_id", document.toString()), term("generation", generation.toString())))));
        });
        if (allowed.isEmpty()) return List.of();
        // Owner-private files are authorized by the explicit owner file mappings, not by Source access.
        return searchPrepared(tenant, query, embeddings.query(query, queryCaller(tenant, actor)), List.of(), null, SearchFilters.NONE, List.of(), allowed, null);
    }

    private List<SearchHit> searchPrepared(TenantId tenant, String query, float[] vector, List<String> mediaTypes, Instant since,
            SearchFilters restrictions, List<String> sourceIds, List<Object> privateFiles, @Nullable Collection<String> accessTokens) {
        List<Object> filters = new ArrayList<>();
        filters.add(term("tenant_id", tenant.value().toString()));
        filters.add(term("index_identity", identity));
        filters.add(privateFiles.isEmpty() ? Map.of("bool", Map.of("must_not", List.of(Map.of("exists", Map.of("field", "user_file_id")))))
                : Map.of("bool", Map.of("should", privateFiles, "minimum_should_match", 1)));
        if (accessTokens != null) filters.add(accessFilter(accessTokens));
        if (!mediaTypes.isEmpty()) filters.add(Map.of("terms", Map.of("media_type", mediaTypes)));
        if (since != null) filters.add(Map.of("range", Map.of("updated_at", Map.of("gte", since.toString()))));
        if (!sourceIds.isEmpty()) {
            List<Object> origins = new ArrayList<>();
            origins.add(Map.of("terms", Map.of("source_metadata.source_id", sourceIds)));
            if (!restrictions.sources().isEmpty()) origins.add(Map.of("terms", Map.of("source_metadata.type",
                    restrictions.sources().stream().map(Enum::name).sorted().toList())));
            // A window must not remove a document that carries no date; see the undated-documents increment.
            if (restrictions.created() != null)
                origins.add(dateRange("source_metadata.created_at", restrictions.created(), true));
            if (restrictions.updated() != null)
                origins.add(dateRange("source_metadata.updated_at", restrictions.updated(),
                        SearchFilters.keepsUndated(restrictions.updated(), Instant.now())));
            filters.add(Map.of("nested", Map.of("path", "source_metadata", "query", Map.of("bool", Map.of("filter", origins)))));
        }
        var response = gateway.json("POST", "/" + readAlias() + "/_search", Map.of("search_pipeline", pipeline()), Map.of(
                "size", properties.candidateLimit(), "_source", Map.of("excludes", List.of("vector")),
                "query", hybridQuery(query, vector, filters)));
        var hits = new ArrayList<SearchHit>();
        for (var hit : response.path("hits").path("hits")) {
            var source = hit.path("_source");
            hits.add(new SearchHit(UUID.fromString(source.path("document_id").asString()),
                    UUID.fromString(source.path("generation").asString()), source.path("ordinal").asInt(),
                    source.path("title").asString(), source.path("media_type").asString(), source.path("content").asString(),
                    source.path("provenance").asString(), Instant.parse(source.path("updated_at").asString()), hit.path("_score").asDouble()));
        }
        return List.copyOf(hits);
    }

    private Object hybridQuery(String query, float[] vector, List<Object> filters) {
        Object filter = Map.of("bool", Map.of("filter", filters));
        Object keyword = Map.of("bool", Map.of("filter", filters, "must", List.of(Map.of("multi_match", Map.of(
                "query", query, "fields", List.of("title^2", "title.folded^2", "content", "content.folded"))))));
        Object semantic = Map.of("knn", Map.of("vector", Map.of(
                "vector", vector, "min_score", properties.minimumSemanticScore(),
                "method_parameters", Map.of("ef_search", properties.candidateLimit()), "filter", filter)));
        return Map.of("hybrid", Map.of("pagination_depth", properties.candidateLimit(), "queries", List.of(keyword, semantic)));
    }

    /** Bounded metadata and ordinal-window query; no embedding or PostgreSQL content load. */
    public SearchDocument document(TenantId tenant, UUID id, UUID generation, int from, int limit) {
        if (from < 0 || from > 9999 || limit < 1 || limit > 20) throw new SearchRequestException();
        if (!gateway.exists("/" + readAlias())) throw new SearchDocumentUnavailableException();
        var response = gateway.json("POST", "/" + readAlias() + "/_search", Map.of(), Map.of(
                "size", 0, "track_total_hits", true,
                "query", Map.of("bool", Map.of("filter", List.of(term("tenant_id", tenant.value().toString()),
                        term("document_id", id.toString()), term("generation", generation.toString()), term("index_identity", identity)))),
                "aggs", Map.of(
                        "header", Map.of("top_hits", Map.of("size", 1, "_source", List.of("title"))),
                        "last", Map.of("max", Map.of("field", "ordinal")),
                        "window", Map.of("filter", Map.of("range", Map.of("ordinal", Map.of("gte", from, "lt", from + limit))),
                                "aggs", Map.of("chunks", Map.of("top_hits", Map.of("size", limit,
                                        "sort", List.of(Map.of("ordinal", "asc")), "_source", List.of("ordinal", "content", "provenance"))))))));
        int total = response.path("hits").path("total").path("value").asInt();
        if (total == 0) throw new SearchDocumentUnavailableException();
        var aggregations = response.path("aggregations");
        if (total > 10000 || aggregations.path("last").path("value").asInt(-1) != total - 1) throw new SearchUnavailableException();
        var passages = new ArrayList<SearchPage.Passage>();
        int expected = from;
        for (var hit : aggregations.path("window").path("chunks").path("hits").path("hits")) {
            var source = hit.path("_source");
            int ordinal = source.path("ordinal").asInt(-1);
            if (ordinal != expected++) throw new SearchUnavailableException();
            passages.add(new SearchPage.Passage(ordinal, source.path("content").asString(), source.path("provenance").asString()));
        }
        int start = Math.min(from, total);
        if (passages.size() != Math.min(limit, total - start)) throw new SearchUnavailableException();
        String title = aggregations.path("header").path("hits").path("hits").path(0).path("_source").path("title").asString();
        return new SearchDocument(id, generation, title, List.copyOf(passages), start, total, start + passages.size() < total);
    }

    /**
     * Rewrites source metadata and access fields of one indexed generation in place with per-chunk partial bulk
     * updates. Chunk IDs come from a bounded search, so the service role needs only its existing search and bulk
     * permissions (update-by-query needs scroll permissions). No embedding is read or generated and the document
     * stays searchable; chunks absent from the index are left to the INDEX path.
     */
    @Override
    public void updateAccess(TenantId tenant, DocumentId document, UUID generation) {
        if (!gateway.exists("/" + identity)) return;
        ensureIndex();
        var origins = sourceSearch.indexMetadata(tenant, document, generation);
        var access = sourceSearch.indexAccess(tenant, document);
        var fields = new HashMap<String, Object>();
        fields.put("source_metadata", metadata(origins));
        fields.put("metadata_hash", metadataHash(origins, access));
        fields.put("access_public", access.everyone());
        fields.put("access_control_list", access.sortedTokens());
        var ids = chunkIds(List.of(term("tenant_id", tenant.value().toString()), term("document_id", document.value().toString()),
                term("generation", generation.toString()), term("index_identity", identity)));
        String update = mapper.writeValueAsString(Map.of("doc", fields));
        // Each partial update re-indexes the whole chunk including its vector, so batches stay bounded like writes.
        for (int offset = 0; offset < ids.size(); offset += ACCESS_UPDATE_BATCH) {
            var batch = ids.subList(offset, Math.min(offset + ACCESS_UPDATE_BATCH, ids.size()));
            var body = new StringBuilder();
            for (String id : batch) {
                body.append(mapper.writeValueAsString(Map.of("update", Map.of("_id", id)))).append('\n').append(update).append('\n');
            }
            var response = gateway.bulk("/" + identity + "/_bulk", body.toString());
            // Every chunk must accept the same fields; a missing chunk means a concurrent rewrite, so the work retries.
            if (response.path("errors").asBoolean(true) || response.path("items").size() != batch.size()) throw new SearchUnavailableException();
            for (JsonNode item : response.path("items")) {
                int status = item.path("update").path("status").asInt();
                if (status < 200 || status >= 300) throw new SearchUnavailableException();
            }
        }
    }

    /** IDs of at most 10,000 matching chunks (the per-document chunk bound), without source or vectors. */
    private List<String> chunkIds(List<Object> filters) {
        var hits = gateway.json("POST", "/" + identity + "/_search", Map.of(), Map.of("size", 10000, "_source", false, "track_total_hits", true,
                "query", Map.of("bool", Map.of("filter", filters)))).path("hits");
        if (hits.path("total").path("value").asInt(0) > 10000) throw new SearchUnavailableException();
        var ids = new ArrayList<String>();
        hits.path("hits").forEach(hit -> ids.add(hit.path("_id").asString()));
        return List.copyOf(ids);
    }

    /** All chunks of the generation are present, regardless of whether their metadata and access are current. */
    @Override
    public boolean containsGeneration(DocumentIndexState document) {
        if (!gateway.exists("/" + readAlias())) return false;
        var count = gateway.json("POST", "/" + readAlias() + "/_count", Map.of(), Map.of("query", Map.of("bool", Map.of(
                "filter", List.of(term("tenant_id", document.tenantId().value().toString()),
                        term("document_id", document.documentId().value().toString()), term("generation", document.generation().toString()),
                        term("index_identity", identity))))));
        return document.chunkCount() > 0 && count.path("count").asInt(-1) == document.chunkCount();
    }

    @Override
    public boolean contains(DocumentIndexState document) {
        if (!gateway.exists("/" + readAlias())) return false;
        String expectedMetadata = metadataHash(sourceSearch.indexMetadata(document.tenantId(), document.documentId(), document.generation()),
                sourceSearch.indexAccess(document.tenantId(), document.documentId()));
        var count = gateway.json("POST", "/" + readAlias() + "/_count", Map.of(), Map.of("query", Map.of("bool", Map.of(
                "filter", List.of(term("tenant_id", document.tenantId().value().toString()),
                        term("document_id", document.documentId().value().toString()), term("generation", document.generation().toString()),
                        term("metadata_hash", expectedMetadata))))));
        return document.chunkCount() > 0 && count.path("count").asInt(-1) == document.chunkCount();
    }

    @Override
    public synchronized void purgeStale() {
        if (!gateway.exists("/" + identity)) { sweepCursor = ""; return; }
        var body = new HashMap<String,Object>();
        body.put("size", 500);
        body.put("sort", List.of(Map.of("chunk_key", "asc")));
        body.put("_source", List.of("tenant_id", "document_id", "generation", "chunk_key"));
        if (!sweepCursor.isEmpty()) body.put("search_after", List.of(sweepCursor));
        var hits = gateway.json("POST", "/" + identity + "/_search", Map.of(), body).path("hits").path("hits");
        var byTenant = new HashMap<TenantId, List<JsonNode>>();
        for (var hit : hits) {
            var source = hit.path("_source");
            var tenant = new TenantId(UUID.fromString(source.path("tenant_id").asString()));
            byTenant.computeIfAbsent(tenant, _ -> new ArrayList<>()).add(source);
        }
        var deletes = new StringBuilder();
        for (var entry : byTenant.entrySet()) {
            var retained = documents.retainedGenerations(entry.getKey(), entry.getValue().stream()
                    .map(s -> UUID.fromString(s.path("document_id").asString())).distinct().toList());
            for (var source : entry.getValue()) {
                var generations = retained.getOrDefault(UUID.fromString(source.path("document_id").asString()), Set.of());
                if (!generations.contains(UUID.fromString(source.path("generation").asString()))) {
                    deletes.append(mapper.writeValueAsString(Map.of("delete", Map.of("_id", source.path("chunk_key").asString())))).append('\n');
                }
            }
        }
        if (!deletes.isEmpty()) {
            var result = gateway.bulk("/" + identity + "/_bulk", deletes.toString());
            if (result.path("errors").asBoolean(true)) throw new SearchUnavailableException();
        }
        sweepCursor = hits.size() < 500 ? "" : hits.get(hits.size() - 1).path("_source").path("chunk_key").asString();
    }

    /**
     * Deletes every indexed generation of the document by ID in bounded bulk batches. Like access refresh this
     * avoids delete-by-query, whose continuation beyond one batch needs scroll permissions.
     */
    @Override
    public void delete(TenantId tenant, DocumentId document) {
        if (!gateway.exists("/" + identity)) return;
        deleteMatching(documentFilter(tenant, document));
    }

    /** Deletes by ID the document's chunks of generations that are neither served nor being indexed. */
    @Override
    public void purgeObsolete(TenantId tenant, DocumentId document) {
        if (!gateway.exists("/" + identity)) return;
        var retained = documents.retainedGenerations(tenant, List.of(document.value())).getOrDefault(document.value(), Set.of());
        var query = documentFilter(tenant, document);
        if (!retained.isEmpty()) {
            query.put("must_not", List.of(Map.of("terms", Map.of("generation", retained.stream().map(UUID::toString).toList()))));
        }
        deleteMatching(query);
    }

    private static Map<String, Object> documentFilter(TenantId tenant, DocumentId document) {
        var query = new HashMap<String, Object>();
        query.put("filter", List.of(term("tenant_id", tenant.value().toString()), term("document_id", document.value().toString())));
        return query;
    }

    private void deleteMatching(Map<String, Object> bool) {
        for (int round = 0; round < 100; round++) {
            var hits = gateway.json("POST", "/" + identity + "/_search", Map.of(), Map.of("size", 1000, "_source", false,
                    "query", Map.of("bool", bool))).path("hits").path("hits");
            if (hits.isEmpty()) return;
            var body = new StringBuilder();
            for (var hit : hits) {
                body.append(mapper.writeValueAsString(Map.of("delete", Map.of("_id", hit.path("_id").asString())))).append('\n');
            }
            // Bulk waits for refresh, so the next search no longer returns deleted chunks.
            var response = gateway.bulk("/" + identity + "/_bulk", body.toString());
            for (JsonNode item : response.path("items")) {
                int status = item.path("delete").path("status").asInt();
                if (status != 404 && (status < 200 || status >= 300)) throw new SearchUnavailableException();
            }
        }
        throw new SearchUnavailableException();
    }

    private static Map<String,Object> term(String field, String value) { return Map.of("term", Map.of(field, value)); }

    /** The range, widened to documents without that date when the shared rule admits them. */
    static Map<String, Object> dateRange(String field, SearchFilters.Interval interval, boolean keepUndated) {
        var within = range(field, interval);
        if (!keepUndated) return within;
        return Map.of("bool", Map.of(
                "should", List.of(within, Map.of("bool", Map.of("must_not", Map.of("exists", Map.of("field", field))))),
                "minimum_should_match", 1));
    }

    private static Map<String, Object> range(String field, SearchFilters.Interval interval) {
        var bounds = new LinkedHashMap<String, String>();
        if (interval.from() != null) bounds.put("gte", interval.from().toString());
        if (interval.to() != null) bounds.put("lte", interval.to().toString());
        return Map.of("range", Map.of(field, bounds));
    }

    private static List<Map<String, Object>> metadata(List<DocumentSourceMetadata> origins) {
        return origins.stream().map(origin -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("source_id", origin.sourceId().toString());
            value.put("item_id", origin.itemId().toString());
            value.put("type", origin.type().name());
            if (origin.createdAt() != null) value.put("created_at", origin.createdAt().toString());
            if (origin.updatedAt() != null) value.put("updated_at", origin.updatedAt().toString());
            value.put("authors", origin.authors());
            return value;
        }).toList();
    }

    /**
     * Readers match public documents or shared tokens. Chunks written before access fields existed stay visible
     * until their ACCESS backfill completes; the post-query database recheck still authorizes every hit.
     */
    private static Map<String, Object> accessFilter(Collection<String> tokens) {
        List<Object> allowed = new ArrayList<>();
        allowed.add(Map.of("term", Map.of("access_public", true)));
        if (!tokens.isEmpty()) allowed.add(Map.of("terms", Map.of("access_control_list", tokens.stream().sorted().toList())));
        allowed.add(Map.of("bool", Map.of("must_not", List.of(Map.of("exists", Map.of("field", "access_public"))))));
        return Map.of("bool", Map.of("should", allowed, "minimum_should_match", 1));
    }

    private String metadataHash(List<DocumentSourceMetadata> origins, DocumentAccess access) {
        try {
            String value = "v2:" + mapper.writeValueAsString(metadata(origins)) + ":"
                    + mapper.writeValueAsString(Map.of("everyone", access.everyone(), "tokens", access.sortedTokens()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
