package io.memoryos.retrieval.opensearch;

import io.memoryos.shared.ActorId;
import io.memoryos.document.DocumentChunk;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.DocumentAccess;
import io.memoryos.connector.DocumentSourceMetadata;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentChunkSet;
import io.memoryos.document.DocumentId;
import io.memoryos.document.DocumentIndexState;
import io.memoryos.shared.Sha256;
import io.memoryos.shared.TenantId;
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
import io.memoryos.retrieval.settings.SearchGeneration;
import io.memoryos.retrieval.settings.SearchGenerations;
import io.memoryos.usage.AiUsageFlow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OpenSearchIndexService implements SearchIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenSearchIndexService.class);
    private final OpenSearchGateway gateway;
    private final SearchGenerations generations;
    private final SearchProperties properties;
    private final ObjectMapper mapper;
    private final DocumentChunkPort documents;
    private final SourceSearchService sourceSearch;
    private final SearchTimings timings;
    private static final int ACCESS_UPDATE_BATCH = 128;
    private final Map<String, String> sweepCursors = new ConcurrentHashMap<>();
    /**
     * Indexes this process has verified against their generation (identity to generation ID). Writes skip the
     * verification afterwards; an index deleted since, by this or another process, is noticed by the write itself.
     */
    private final Map<String, UUID> ensured = new ConcurrentHashMap<>();
    /** One verification or creation at a time per index; other indexes and verified writes are not held up. */
    private final Map<String, Object> ensuring = new ConcurrentHashMap<>();

    public OpenSearchIndexService(OpenSearchGateway gateway, SearchGenerations generations,
            SearchProperties properties, ObjectMapper mapper, DocumentChunkPort documents, SourceSearchService sourceSearch, SearchTimings timings) {
        this.gateway = gateway; this.generations = generations; this.properties = properties; this.mapper = mapper;
        this.documents = documents;
        this.sourceSearch = sourceSearch;
        this.timings = timings;
    }

    /** The PRESENT generation's index: the one searches read and new chunks are written to. */
    @Override public String identity() { return generations.present().identity(); }
    @Override public List<String> identities() { return generations.identities(); }

    /** An active generation's index; work for an index that is no longer PRESENT or FUTURE cannot proceed. */
    private SearchGenerations.Active resolve(String identity) {
        return generations.active(identity).orElseThrow(SearchUnavailableException::new);
    }
    public int candidateLimit() { return properties.candidateLimit(); }
    private static String readAlias(String identity) { return identity + "-read"; }
    /** Chunk writes go through this alias, so a write to a deleted index fails rather than recreating it. */
    private static String writeAlias(String identity) { return identity + "-write"; }
    /** The named hybrid pipeline earlier versions created per index; searches now send it inline, cleanup still removes it. */
    private static String pipeline(String identity) { return identity + "-hybrid"; }

    /**
     * Seeds or loads the PRESENT generation at startup and checks an existing index against it, so a mismatch is
     * reported when the process starts rather than at the first search. Neither an unreachable OpenSearch nor a
     * mismatch stops the process; every search and indexing call keeps failing loudly until it is resolved.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyOnStartup() {
        String identity = null;
        try {
            for (String active : generations.identities()) {
                identity = active;
                var generation = resolve(active);
                if (gateway.exists("/" + identity)) ensureIndex(generation, false);
            }
        } catch (SearchUnavailableException unavailable) {
            LOGGER.atWarn().addKeyValue("event", "search.index.unverified").addKeyValue("identity", identity)
                    .log("Could not verify the search index against its generation at startup");
        }
    }

    public void ensureIndex() { ensureIndex(generations.present(), true); }

    /** Creates the index of a generation about to become FUTURE, or verifies an existing one against it. */
    public void createIndex(SearchGenerations.Active generation) { ensureIndex(generation, true); }

    public boolean indexExists(String identity) { return gateway.exists("/" + identity); }

    /**
     * Deletes a generation's index and any named hybrid pipeline an earlier version created (the read alias goes with
     * the index); a missing one counts as deleted. Returns whether the index is gone afterwards, which is the recount a
     * cleanup relies on.
     */
    public boolean deleteIndex(String identity) {
        gateway.delete("/" + identity);
        gateway.delete("/_search/pipeline/" + pipeline(identity));
        sweepCursors.remove(identity);
        ensured.remove(identity);
        return !gateway.exists("/" + identity);
    }

    /** Verifies the index once per process and generation; later writes go straight to it. */
    private void ensureWritable(SearchGenerations.Active active) {
        if (active.generation().id().equals(ensured.get(active.identity()))) return;
        ensureIndex(active, false);
    }

    /** Forgets the verification of an index a write found missing, so the next ensure checks and creates it again. */
    private void invalidate(String identity) { ensured.remove(identity); }

    /**
     * Only PRESENT creates its index on first write, as a fresh deployment needs. A FUTURE index is created with its
     * generation; one missing later was cancelled. Writes go through the write alias with {@code require_alias}, so a
     * FUTURE deleted after this check still cannot be recreated as an unmapped index by a write already under way.
     * The explicit calls (startup, settings) always verify; writes do so once per process and generation.
     */
    private void ensureIndex(SearchGenerations.Active active, boolean create) {
        synchronized (ensuring.computeIfAbsent(active.identity(), _ -> new Object())) {
            // A write that waited for another thread's verification of the same generation needs none of its own.
            if (!create && active.generation().id().equals(ensured.get(active.identity()))) return;
            try {
                verifyIndex(active, create);
            } catch (RuntimeException failure) {
                ensured.remove(active.identity());
                throw failure;
            }
            ensured.put(active.identity(), active.generation().id());
        }
    }

    private void verifyIndex(SearchGenerations.Active active, boolean create) {
        var generation = active.generation();
        String identity = active.identity();
        if (!gateway.exists("/" + identity)) {
            if (!create && generation.status() != SearchGeneration.Status.PRESENT) throw new SearchUnavailableException();
            var fields = new HashMap<String, Object>();
            for (String field : List.of("tenant_id", "document_id", "generation", "content_hash", "chunk_key", "media_type", "index_identity")) {
                fields.put(field, Map.of("type", "keyword"));
            }
            fields.put("title", textMapping());
            fields.put("content", textMapping());
            fields.put("ordinal", Map.of("type", "integer"));
            fields.put("updated_at", Map.of("type", "date"));
            fields.put("provenance", Map.of("type", "text", "index", false));
            fields.put("vector", Map.of("type", "knn_vector", "dimension", generation.dimensions(),
                    "method", Map.of("name", "hnsw", "engine", "faiss", "space_type", "cosinesimil",
                            "parameters", Map.of("ef_construction", 256, "m", 32))));
            gateway.json("PUT", "/" + identity, Map.of(), Map.of(
                    "settings", Map.of("index.knn", true, "number_of_shards", 1, "number_of_replicas", properties.replicas(),
                            "analysis", Map.of("analyzer", Map.of("folded", Map.of("tokenizer", "standard", "filter", List.of("lowercase", "asciifolding"))))),
                    "mappings", Map.of("dynamic", "strict", "_meta", meta(generation), "properties", fields),
                    "aliases", Map.of(readAlias(identity), Map.of(), writeAlias(identity), Map.of())));
        }
        var mapping = gateway.json("GET", "/" + identity + "/_mapping", Map.of(), null).path(identity).path("mappings");
        verifyGeneration(generation, mapping);
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
        // An index created before the write alias existed receives it here, on its first verification or write.
        for (String alias : List.of(readAlias(identity), writeAlias(identity))) {
            if (!gateway.exists("/" + alias)) gateway.json("PUT", "/" + identity + "/_alias/" + alias, Map.of(), Map.of());
            var aliases = gateway.json("GET", "/" + alias + "/_alias/" + alias, Map.of(), null);
            if (aliases.size() != 1 || !aliases.has(identity)) throw new SearchUnavailableException();
        }
    }

    /** Min-max normalization and the configured lexical/vector weights, sent with each hybrid search. */
    private Map<String, Object> hybridPipeline() {
        return Map.of("phase_results_processors", List.of(
                Map.of("normalization-processor", Map.of("normalization", Map.of("technique", "min_max"),
                        "combination", Map.of("technique", "arithmetic_mean", "parameters",
                                Map.of("weights", List.of(properties.keywordWeight(), 1 - properties.keywordWeight())))))));
    }

    /** What decides the vectors in this index; {@link #verifyGeneration} holds the index to it. */
    private static Map<String, Object> meta(SearchGeneration generation) {
        return Map.of("identity", generation.identity(), "generation", generation.id().toString(),
                "model", generation.model(), "dimensions", generation.dimensions(),
                "document_prefix", generation.documentPrefix(), "chunk_convention", generation.chunkConvention());
    }

    /**
     * Fails loudly when the index was built for other vectors than its generation describes. An index created before
     * generations recorded only its identity and model; when those and the vector dimension agree, the remaining
     * fields are recorded once, since the seeded generation describes exactly what built that index.
     */
    private void verifyGeneration(SearchGeneration generation, JsonNode mapping) {
        var meta = mapping.path("_meta");
        int dimension = mapping.path("properties").path("vector").path("dimension").asInt();
        mismatch(generation, "identity", generation.identity(), meta.path("identity").asString(""));
        mismatch(generation, "model", generation.model(), meta.path("model").asString(""));
        mismatch(generation, "vector_dimension", Integer.toString(generation.dimensions()), Integer.toString(dimension));
        if (!meta.has("chunk_convention")) {
            gateway.json("PUT", "/" + generation.identity() + "/_mapping", Map.of(), Map.of("_meta", meta(generation)));
            LOGGER.atInfo().addKeyValue("event", "search.index.generation_recorded")
                    .addKeyValue("identity", generation.identity()).addKeyValue("generation", generation.id())
                    .log("Recorded the search generation in an index created before generations");
            return;
        }
        mismatch(generation, "dimensions", Integer.toString(generation.dimensions()), meta.path("dimensions").asString(""));
        mismatch(generation, "document_prefix", generation.documentPrefix(), meta.path("document_prefix").asString(""));
        mismatch(generation, "chunk_convention", generation.chunkConvention(), meta.path("chunk_convention").asString(""));
        // Another generation's index, even one with the same vectors, is never read or written as this one's.
        mismatch(generation, "generation", generation.id().toString(), meta.path("generation").asString(""));
    }

    private static void mismatch(SearchGeneration generation, String field, String expected, String actual) {
        if (expected.equals(actual)) return;
        // Model names, dimensions, prefixes and conventions are configuration, not secrets or document content.
        LOGGER.atError().addKeyValue("event", "search.index.generation_mismatch")
                .addKeyValue("identity", generation.identity()).addKeyValue("generation", generation.id())
                .addKeyValue("field", field).addKeyValue("expected", expected).addKeyValue("actual", actual)
                .log("The search index does not match its search generation; search and indexing stay unavailable");
        throw new SearchUnavailableException();
    }

    private static Map<String,Object> textMapping() {
        return Map.of("type", "text", "fields", Map.of("folded", Map.of("type", "text", "analyzer", "folded")));
    }

    /** Writes to the PRESENT index. */
    public void index(DocumentChunkSet document) { index(document, identity()); }

    @Override
    public void index(DocumentChunkSet document, String identity) {
        var active = resolve(identity);
        ensureWritable(active);
        if (write(active, document)) return;
        // The index was deleted after this process verified it. PRESENT is created again; a FUTURE was cancelled and
        // its ensure refuses. One more attempt only: a second disappearance is a failure like any other.
        invalidate(identity);
        ensureIndex(active, false);
        if (!write(active, document)) throw new SearchUnavailableException();
    }

    /** Writes every chunk and checks the count; false when the index turned out to be missing. */
    private boolean write(SearchGenerations.Active active, DocumentChunkSet document) {
        String identity = active.identity();
        var embeddings = active.embeddings();
        var origins = sourceSearch.indexMetadata(document.tenantId(), document.documentId(), document.generation());
        var sourceMetadata = metadata(origins);
        var access = sourceSearch.indexAccess(document.tenantId(), document.documentId());
        String metadataHash = metadataHash(origins, access);
        for (int offset = 0; offset < document.chunks().size(); offset += embeddings.batchSize()) {
            var batch = document.chunks().subList(offset, Math.min(offset + embeddings.batchSize(), document.chunks().size()));
            var found = existing(active, document, batch);
            if (found == null) return false;
            var missing = batch.stream().filter(chunk -> !found.containsKey(chunk.contentSha256())).toList();
            if (!missing.isEmpty()) {
                var generated = embeddings.documents(missing.stream().map(DocumentChunk::content).toList(),
                        new ValidatedEmbeddingService.Caller(document.tenantId().value(), null, AiUsageFlow.EMBEDDING_INDEXING));
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
            // A cancelled FUTURE's index can be deleted while its chunks are embedded; the alias went with it.
            var response = gateway.bulkThroughAlias(writeAlias(identity), body.toString());
            if (OpenSearchGateway.indexMissing(response)) return false;
            if (response.path("errors").asBoolean(true) || response.path("items").size() != batch.size()) throw new SearchUnavailableException();
            int acknowledged = 0;
            for (JsonNode item : response.path("items")) {
                int status = item.path("index").path("status").asInt();
                if (status < 200 || status >= 300 || !document.chunkId(batch.get(acknowledged++).ordinal())
                        .equals(item.path("index").path("_id").asString())) throw new SearchUnavailableException();
            }
        }
        var count = gateway.jsonOrMissing("POST", "/" + readAlias(identity) + "/_count", Map.of(),
                Map.of("query", Map.of("bool", Map.of("filter", List.of(
                        term("tenant_id", document.tenantId().value().toString()), term("document_id", document.documentId().value().toString()),
                        term("generation", document.generation().toString()), term("index_identity", identity))))));
        if (OpenSearchGateway.indexMissing(count)) return false;
        if (count.path("count").asInt(-1) != document.chunks().size()) throw new SearchUnavailableException();
        return true;
    }

    /** Reusable vectors by content hash; null when the index is missing. */
    private @Nullable Map<String,float[]> existing(SearchGenerations.Active active, DocumentChunkSet document, List<DocumentChunk> chunks) {
        String identity = active.identity();
        // Surviving vectors in this same model space also cover metadata-only changes.
        var response = gateway.jsonOrMissing("POST", "/" + identity + "/_search", Map.of(), Map.of(
                "size", chunks.size(), "_source", List.of("content_hash", "vector"),
                "collapse", Map.of("field", "content_hash"),
                "query", Map.of("bool", Map.of("filter", List.of(term("tenant_id", document.tenantId().value().toString()),
                        term("document_id", document.documentId().value().toString()), term("index_identity", identity),
                        Map.of("terms", Map.of("content_hash", chunks.stream().map(DocumentChunk::contentSha256).distinct().toList())))))));
        if (OpenSearchGateway.indexMissing(response)) return null;
        var result = new HashMap<String,float[]>();
        for (var hit : response.path("hits").path("hits")) {
            var source = hit.path("_source");
            var raw = source.path("vector");
            if (raw.size() != active.generation().dimensions()) continue;
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
        var active = generations.present();
        return searchPrepared(active, tenant, query, active.embeddings().query(query, queryCaller(tenant, actor)), mediaTypes, since,
                SearchFilters.NONE, List.of(), accessTokens);
    }

    /** Search a pre-authorized Source scope; an empty scope intentionally produces no indexed results. */
    public List<SearchHit> search(SourceSearchScope scope, String query, List<String> mediaTypes, Instant since) {
        var active = generations.present();
        if (scope.sources().isEmpty()) return List.of();
        return searchPrepared(active, scope.tenant(), query, active.embeddings().query(query), mediaTypes, since, SearchFilters.NONE,
                scope.sources().keySet().stream().map(UUID::toString).toList(), scope.accessTokens());
    }

    /** Embed each distinct text once for this Search call. */
    public List<List<SearchHit>> batch(SourceSearchScope scope, List<SearchQuery> queries, SearchFilters filters, Runnable checkActive) {
        if (queries.isEmpty() || queries.size() > 8) throw new SearchRequestException();
        checkActive.run();
        var active = generations.present();
        var embeddings = active.embeddings();
        var texts = queries.stream().map(SearchQuery::text).distinct().toList();
        var vectors = new LinkedHashMap<String, float[]>();
        for (int offset = 0; offset < texts.size(); offset += embeddings.batchSize()) {
            checkActive.run();
            var inputs = texts.subList(offset, Math.min(offset + embeddings.batchSize(), texts.size()));
            var output = timings.measure(SearchTimings.Stage.EMBEDDING, () -> embeddings.queries(inputs, queryCaller(scope.tenant(), scope.actor())));
            for (int i = 0; i < inputs.size(); i++) vectors.put(inputs.get(i), output.get(i));
        }
        List<Callable<List<SearchHit>>> tasks = texts.stream().<Callable<List<SearchHit>>>map(text ->
                () -> timings.measure(SearchTimings.Stage.HYBRID, () -> searchPrepared(active, scope.tenant(), text, vectors.get(text), List.of(), null, filters,
                        scope.sources().keySet().stream().map(UUID::toString).toList(), scope.accessTokens()))).toList();
        var results = SearchTasks.run(tasks, checkActive);
        // The adapter uses the same hybrid request for both groups. Reuse identical IO but retain
        // each group's rank list and weight for fusion.
        return queries.stream().map(query -> results.get(texts.indexOf(query.text()))).toList();
    }

    private List<SearchHit> searchPrepared(SearchGenerations.Active active, TenantId tenant, String query, float[] vector,
            List<String> mediaTypes, Instant since, SearchFilters restrictions, List<String> sourceIds, Collection<String> accessTokens) {
        return searchPrepared(active, tenant, query, vector, mediaTypes, since, restrictions, sourceIds, List.of(), accessTokens);
    }

    private static ValidatedEmbeddingService.@Nullable Caller queryCaller(TenantId tenant, @Nullable ActorId actor) {
        return actor == null ? null : new ValidatedEmbeddingService.Caller(tenant.value(), actor.value(), AiUsageFlow.EMBEDDING_QUERY);
    }

    public List<SearchHit> searchFiles(TenantId tenant, String query, Map<UUID, UUID> generations, Map<UUID, UUID> files) {
        return searchFiles(tenant, null, query, generations, files);
    }

    public List<SearchHit> searchFiles(TenantId tenant, @Nullable ActorId actor, String query, Map<UUID, UUID> generations, Map<UUID, UUID> files) {
        var active = this.generations.present();
        if (generations.isEmpty() || files.isEmpty()) return List.of();
        List<Object> allowed = new ArrayList<>();
        files.forEach((file, document) -> {
            var generation = generations.get(document);
            if (generation != null) allowed.add(Map.of("bool", Map.of("filter", List.of(term("user_file_id", file.toString()),
                    term("document_id", document.toString()), term("generation", generation.toString())))));
        });
        if (allowed.isEmpty()) return List.of();
        // Owner-private files are authorized by the explicit owner file mappings, not by Source access.
        return searchPrepared(active, tenant, query, active.embeddings().query(query, queryCaller(tenant, actor)), List.of(), null,
                SearchFilters.NONE, List.of(), allowed, null);
    }

    private List<SearchHit> searchPrepared(SearchGenerations.Active active, TenantId tenant, String query, float[] vector,
            List<String> mediaTypes, Instant since, SearchFilters restrictions, List<String> sourceIds, List<Object> privateFiles,
            @Nullable Collection<String> accessTokens) {
        String identity = active.identity();
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
        // No existence check first: an index not created yet (a fresh deployment before its first write) has no results.
        // The normalization travels with the request, so a search depends on no cluster-side pipeline object.
        var response = gateway.jsonOrMissing("POST", "/" + readAlias(identity) + "/_search", Map.of(), Map.of(
                "size", properties.candidateLimit(), "_source", Map.of("excludes", List.of("vector")),
                "search_pipeline", hybridPipeline(),
                "query", hybridQuery(query, vector, filters, active.generation().minimumSemanticScore())));
        if (OpenSearchGateway.indexMissing(response)) return List.of();
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

    private Object hybridQuery(String query, float[] vector, List<Object> filters, double minimumSemanticScore) {
        Object filter = Map.of("bool", Map.of("filter", filters));
        Object keyword = Map.of("bool", Map.of("filter", filters, "must", List.of(Map.of("multi_match", Map.of(
                "query", query, "fields", List.of("title^2", "title.folded^2", "content", "content.folded"))))));
        Object semantic = Map.of("knn", Map.of("vector", Map.of(
                "vector", vector, "min_score", minimumSemanticScore,
                "method_parameters", Map.of("ef_search", properties.candidateLimit()), "filter", filter)));
        return Map.of("hybrid", Map.of("pagination_depth", properties.candidateLimit(), "queries", List.of(keyword, semantic)));
    }

    /** Bounded metadata and ordinal-window query; no embedding or PostgreSQL content load. */
    public SearchDocument document(TenantId tenant, UUID id, UUID generation, int from, int limit) {
        if (from < 0 || from > 9999 || limit < 1 || limit > 20) throw new SearchRequestException();
        String identity = identity();
        var response = gateway.jsonOrMissing("POST", "/" + readAlias(identity) + "/_search", Map.of(), Map.of(
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
        if (OpenSearchGateway.indexMissing(response) || total == 0) throw new SearchDocumentUnavailableException();
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
    public void updateAccess(TenantId tenant, DocumentId document, UUID generation) {
        updateAccess(tenant, document, generation, identity());
    }

    @Override
    public void updateAccess(TenantId tenant, DocumentId document, UUID generation, String identity) {
        var active = resolve(identity);
        // Nothing is indexed in a missing index; only an index this process has not verified yet is checked first.
        if (!active.generation().id().equals(ensured.get(identity))) {
            if (!gateway.exists("/" + identity)) return;
            ensureWritable(active);
        }
        var origins = sourceSearch.indexMetadata(tenant, document, generation);
        var access = sourceSearch.indexAccess(tenant, document);
        var fields = new HashMap<String, Object>();
        fields.put("source_metadata", metadata(origins));
        fields.put("metadata_hash", metadataHash(origins, access));
        fields.put("access_public", access.everyone());
        fields.put("access_control_list", access.sortedTokens());
        var ids = chunkIds(identity, List.of(term("tenant_id", tenant.value().toString()), term("document_id", document.value().toString()),
                term("generation", generation.toString()), term("index_identity", identity)));
        if (ids == null) { invalidate(identity); return; }
        String update = mapper.writeValueAsString(Map.of("doc", fields));
        // Each partial update re-indexes the whole chunk including its vector, so batches stay bounded like writes.
        for (int offset = 0; offset < ids.size(); offset += ACCESS_UPDATE_BATCH) {
            var batch = ids.subList(offset, Math.min(offset + ACCESS_UPDATE_BATCH, ids.size()));
            var body = new StringBuilder();
            for (String id : batch) {
                body.append(mapper.writeValueAsString(Map.of("update", Map.of("_id", id)))).append('\n').append(update).append('\n');
            }
            var response = gateway.bulkThroughAlias(writeAlias(identity), body.toString());
            if (OpenSearchGateway.indexMissing(response)) { invalidate(identity); return; }
            // Every chunk must accept the same fields; a missing chunk means a concurrent rewrite, so the work retries.
            if (response.path("errors").asBoolean(true) || response.path("items").size() != batch.size()) throw new SearchUnavailableException();
            for (JsonNode item : response.path("items")) {
                int status = item.path("update").path("status").asInt();
                if (status < 200 || status >= 300) throw new SearchUnavailableException();
            }
        }
    }

    /** IDs of at most 10,000 matching chunks (the per-document chunk bound), without source or vectors; null when the index is missing. */
    private @Nullable List<String> chunkIds(String identity, List<Object> filters) {
        var response = gateway.jsonOrMissing("POST", "/" + identity + "/_search", Map.of(), Map.of("size", 10000, "_source", false,
                "track_total_hits", true, "query", Map.of("bool", Map.of("filter", filters))));
        if (OpenSearchGateway.indexMissing(response)) return null;
        var hits = response.path("hits");
        if (hits.path("total").path("value").asInt(0) > 10000) throw new SearchUnavailableException();
        var ids = new ArrayList<String>();
        hits.path("hits").forEach(hit -> ids.add(hit.path("_id").asString()));
        return List.copyOf(ids);
    }

    /** All chunks of the generation are present, regardless of whether their metadata and access are current. */
    public boolean containsGeneration(DocumentIndexState document) {
        return inspect(List.of(document), identity()).get(document.documentId()) != Projection.INCOMPLETE;
    }

    /** All chunks of the generation are present with the current metadata and access fields. */
    public boolean contains(DocumentIndexState document) {
        return inspect(List.of(document), identity()).get(document.documentId()) == Projection.CURRENT;
    }

    /**
     * One read of the expected metadata and access per Tenant of the page and one aggregation over the index: chunk
     * counts per document, generation and metadata hash. The index has one shard, so the counts are exact.
     */
    @Override
    public Map<DocumentId, Projection> inspect(List<DocumentIndexState> page, String identity) {
        if (page.isEmpty()) return Map.of();
        if (page.size() > 1000) throw new IllegalArgumentException("document batch exceeds 1000");
        var expected = new HashMap<DocumentId, String>();
        var byTenant = new LinkedHashMap<TenantId, Map<DocumentId, UUID>>();
        page.forEach(document -> byTenant.computeIfAbsent(document.tenantId(), _ -> new LinkedHashMap<>())
                .put(document.documentId(), document.generation()));
        byTenant.forEach((tenant, generations) -> {
            var origins = sourceSearch.indexMetadata(tenant, generations);
            var access = sourceSearch.indexAccess(tenant, generations.keySet());
            generations.keySet().forEach(document -> expected.put(document, metadataHash(origins.get(document), access.get(document))));
        });
        Map<String, Object> byHash = Map.of("terms", Map.of("field", "metadata_hash", "size", 100));
        Map<String, Object> byGeneration = Map.of("terms", Map.of("field", "generation", "size", 100), "aggs", Map.of("hashes", byHash));
        Map<String, Object> byTenantId = Map.of("terms", Map.of("field", "tenant_id", "size", 10), "aggs", Map.of("generations", byGeneration));
        Map<String, Object> byDocument = Map.of("terms", Map.of("field", "document_id", "size", page.size()), "aggs", Map.of("tenants", byTenantId));
        var response = gateway.jsonOrMissing("POST", "/" + readAlias(identity) + "/_search", Map.of(), Map.of(
                "size", 0,
                "query", Map.of("bool", Map.of("filter", List.of(
                        Map.of("terms", Map.of("tenant_id", byTenant.keySet().stream().map(tenant -> tenant.value().toString()).toList())),
                        Map.of("terms", Map.of("document_id", page.stream().map(document -> document.documentId().value().toString()).distinct().toList())),
                        term("index_identity", identity)))),
                "aggs", Map.of("documents", byDocument)));
        // Chunk counts by tenant/document/generation, and by that key plus metadata hash.
        var counts = new HashMap<String, Long>();
        for (var document : response.path("aggregations").path("documents").path("buckets")) {
            for (var tenant : document.path("tenants").path("buckets")) {
                for (var generation : tenant.path("generations").path("buckets")) {
                    String key = tenant.path("key").asString() + "/" + document.path("key").asString() + "/" + generation.path("key").asString();
                    counts.put(key, generation.path("doc_count").asLong());
                    for (var hash : generation.path("hashes").path("buckets")) {
                        counts.put(key + "/" + hash.path("key").asString(), hash.path("doc_count").asLong());
                    }
                }
            }
        }
        var result = new HashMap<DocumentId, Projection>();
        for (var document : page) {
            String key = document.tenantId().value() + "/" + document.documentId().value() + "/" + document.generation();
            long chunks = document.chunkCount();
            Projection projection = Projection.INCOMPLETE;
            if (chunks > 0 && counts.getOrDefault(key + "/" + expected.get(document.documentId()), 0L) == chunks) projection = Projection.CURRENT;
            else if (chunks > 0 && counts.getOrDefault(key, 0L) == chunks) projection = Projection.STALE_FIELDS;
            result.put(document.documentId(), projection);
        }
        return Map.copyOf(result);
    }

    public void purgeStale() { purgeStale(identity()); }

    @Override
    public synchronized void purgeStale(String identity) {
        String sweepCursor = sweepCursors.getOrDefault(identity, "");
        var body = new HashMap<String,Object>();
        body.put("size", 500);
        body.put("sort", List.of(Map.of("chunk_key", "asc")));
        body.put("_source", List.of("tenant_id", "document_id", "generation", "chunk_key"));
        if (!sweepCursor.isEmpty()) body.put("search_after", List.of(sweepCursor));
        var response = gateway.jsonOrMissing("POST", "/" + identity + "/_search", Map.of(), body);
        if (OpenSearchGateway.indexMissing(response)) { sweepCursors.remove(identity); return; }
        var hits = response.path("hits").path("hits");
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
        sweepCursors.put(identity, hits.size() < 500 ? "" : hits.get(hits.size() - 1).path("_source").path("chunk_key").asString());
    }

    /**
     * Deletes every indexed generation of the document by ID in bounded bulk batches. Like access refresh this
     * avoids delete-by-query, whose continuation beyond one batch needs scroll permissions.
     */
    public void delete(TenantId tenant, DocumentId document) { delete(tenant, document, identity()); }

    @Override
    public void delete(TenantId tenant, DocumentId document, String identity) {
        deleteMatching(identity, documentFilter(tenant, document));
    }

    /** Deletes by ID the document's chunks of generations that are neither served nor being indexed. */
    public void purgeObsolete(TenantId tenant, DocumentId document) { purgeObsolete(tenant, document, identity()); }

    @Override
    public void purgeObsolete(TenantId tenant, DocumentId document, String identity) {
        var retained = documents.retainedGenerations(tenant, List.of(document.value())).getOrDefault(document.value(), Set.of());
        var query = documentFilter(tenant, document);
        if (!retained.isEmpty()) {
            query.put("must_not", List.of(Map.of("terms", Map.of("generation", retained.stream().map(UUID::toString).toList()))));
        }
        deleteMatching(identity, query);
    }

    private static Map<String, Object> documentFilter(TenantId tenant, DocumentId document) {
        var query = new HashMap<String, Object>();
        query.put("filter", List.of(term("tenant_id", tenant.value().toString()), term("document_id", document.value().toString())));
        return query;
    }

    /** Deletes the matching chunks by ID; a missing index holds none. */
    private void deleteMatching(String identity, Map<String, Object> bool) {
        for (int round = 0; round < 100; round++) {
            var found = gateway.jsonOrMissing("POST", "/" + identity + "/_search", Map.of(), Map.of("size", 1000, "_source", false,
                    "query", Map.of("bool", bool)));
            var hits = found.path("hits").path("hits");
            if (OpenSearchGateway.indexMissing(found) || hits.isEmpty()) return;
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
        return Sha256.hex("v2:" + mapper.writeValueAsString(metadata(origins)) + ":"
                + mapper.writeValueAsString(Map.of("everyone", access.everyone(), "tokens", access.sortedTokens())));
    }
}
