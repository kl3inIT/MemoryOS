package io.memoryos.retrieval.opensearch;

import io.memoryos.document.DocumentChunk;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.DocumentSourceMetadata;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentChunkSet;
import io.memoryos.document.DocumentId;
import io.memoryos.document.DocumentIndexState;
import io.memoryos.iam.TenantId;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.Map;
import java.util.UUID;
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
        if (!mapping.path("properties").has("source_metadata")) gateway.json("PUT", "/" + identity + "/_mapping", Map.of(),
                Map.of("properties", Map.of("metadata_hash", Map.of("type", "keyword"), "source_metadata", Map.of(
                        "type", "nested", "properties", Map.of("source_id", Map.of("type", "keyword"),
                                "item_id", Map.of("type", "keyword"), "type", Map.of("type", "keyword"),
                                "created_at", Map.of("type", "date"), "updated_at", Map.of("type", "date"),
                                "authors", Map.of("type", "text", "index", false))))));
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
        String metadataHash = metadataHash(origins);
        for (int offset = 0; offset < document.chunks().size(); offset += embeddings.batchSize()) {
            var batch = document.chunks().subList(offset, Math.min(offset + embeddings.batchSize(), document.chunks().size()));
            var found = existing(document, batch);
            var missing = batch.stream().filter(chunk -> !found.containsKey(chunk.contentSha256())).toList();
            if (!missing.isEmpty()) {
                var generated = embeddings.batch(missing.stream().map(DocumentChunk::content).toList());
                for (int index = 0; index < missing.size(); index++) found.put(missing.get(index).contentSha256(), generated.get(index));
            }
            var body = new StringBuilder();
            for (var chunk : batch) {
                body.append(mapper.writeValueAsString(Map.of("index", Map.of("_id", document.chunkId(chunk.ordinal()))))).append('\n');
                var source = new HashMap<String,Object>();
                source.put("tenant_id", document.tenantId().value().toString());
                source.put("document_id", document.documentId().value().toString());
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

    public List<SearchHit> search(TenantId tenant, String query, List<String> mediaTypes, Instant since) {
        if (!gateway.exists("/" + readAlias())) return List.of();
        return searchPrepared(tenant, query, embeddings.query(query), mediaTypes, since, SearchFilters.NONE, List.of());
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
            var output = timings.measure(SearchTimings.Stage.EMBEDDING, () -> embeddings.batch(inputs));
            for (int i = 0; i < inputs.size(); i++) vectors.put(inputs.get(i), output.get(i));
        }
        List<Callable<List<SearchHit>>> tasks = texts.stream().<Callable<List<SearchHit>>>map(text ->
                () -> timings.measure(SearchTimings.Stage.HYBRID, () -> searchPrepared(scope.tenant(), text, vectors.get(text), List.of(), null, filters,
                        scope.sources().keySet().stream().map(UUID::toString).toList()))).toList();
        var results = SearchTasks.run(tasks, checkActive);
        // The adapter uses the same hybrid request for both groups. Reuse identical IO but retain
        // each group's rank list and weight for fusion.
        return queries.stream().map(query -> results.get(texts.indexOf(query.text()))).toList();
    }

    private List<SearchHit> searchPrepared(TenantId tenant, String query, float[] vector,
            List<String> mediaTypes, Instant since, SearchFilters restrictions, List<String> sourceIds) {
        List<Object> filters = new ArrayList<>();
        filters.add(term("tenant_id", tenant.value().toString()));
        filters.add(term("index_identity", identity));
        if (!mediaTypes.isEmpty()) filters.add(Map.of("terms", Map.of("media_type", mediaTypes)));
        if (since != null) filters.add(Map.of("range", Map.of("updated_at", Map.of("gte", since.toString()))));
        if (!sourceIds.isEmpty()) {
            List<Object> origins = new ArrayList<>();
            origins.add(Map.of("terms", Map.of("source_metadata.source_id", sourceIds)));
            if (!restrictions.sources().isEmpty()) origins.add(Map.of("terms", Map.of("source_metadata.type",
                    restrictions.sources().stream().map(Enum::name).sorted().toList())));
            if (restrictions.created() != null) origins.add(range("source_metadata.created_at", restrictions.created()));
            if (restrictions.updated() != null) origins.add(range("source_metadata.updated_at", restrictions.updated()));
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

    @Override
    public boolean contains(DocumentIndexState document) {
        if (!gateway.exists("/" + readAlias())) return false;
        String expectedMetadata = metadataHash(sourceSearch.indexMetadata(document.tenantId(), document.documentId(), document.generation()));
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
            var current = documents.currentGenerations(entry.getKey(), entry.getValue().stream()
                    .map(s -> UUID.fromString(s.path("document_id").asString())).distinct().toList(), "");
            for (var source : entry.getValue()) {
                var generation = current.get(UUID.fromString(source.path("document_id").asString()));
                if (generation == null || !generation.toString().equals(source.path("generation").asString())) {
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

    @Override
    public void delete(TenantId tenant, DocumentId document) {
        if (!gateway.exists("/" + identity)) return;
        var result = gateway.json("POST", "/" + identity + "/_delete_by_query", Map.of("refresh", "true", "conflicts", "proceed"),
                Map.of("query", Map.of("bool", Map.of("filter", List.of(term("tenant_id", tenant.value().toString()),
                        term("document_id", document.value().toString()))))));
        if (!result.path("failures").isEmpty() || result.path("version_conflicts").asInt(0) > 0) throw new SearchUnavailableException();
    }

    private static Map<String,Object> term(String field, String value) { return Map.of("term", Map.of(field, value)); }

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

    private String metadataHash(List<DocumentSourceMetadata> origins) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("v1:" + mapper.writeValueAsString(metadata(origins))).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
