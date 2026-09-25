package io.memoryos.retrieval.opensearch;

import io.memoryos.shared.ActorId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.memoryos.document.DocumentChunk;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentChunkSet;
import io.memoryos.document.DocumentId;
import io.memoryos.document.DocumentIndexState;
import io.memoryos.document.application.StructuredDocumentChunker;
import io.memoryos.shared.TenantId;
import io.memoryos.retrieval.embedding.ValidatedEmbeddingService;
import io.memoryos.retrieval.settings.SearchGenerations;
import io.memoryos.retrieval.SearchUnavailableException;
import io.memoryos.retrieval.SearchFilters;
import io.memoryos.retrieval.SearchQuery;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.DocumentAccess;
import io.memoryos.connector.DocumentSourceMetadata;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class OpenSearchRetrievalIntegrationTest {
    // JUnit's Testcontainers extension starts and closes this shared container.
    @Container
    @SuppressWarnings("resource") // JUnit's Testcontainers extension owns start/stop.
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>("opensearchproject/opensearch:3.8.0@sha256:bcc1797519726ceb6d651d4a3e60b7c30da91793914a8dfe75fd441d4f641509")
            .withEnv("discovery.type", "single-node").withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true").withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200).waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(3)));

    @Test
    void indexes3072DimensionsFusesKeywordAndSemanticResultsReusesVectorsAndRepairsProjection() throws Exception {
        var config = new SearchInfrastructureConfiguration();
        var properties = new SearchProperties(new URI("http", null, OPENSEARCH.getHost(), OPENSEARCH.getMappedPort(9200), null, null, null),
                "", "", "", "https://api.openai.com/v1", "", "text-embedding-3-large", 3072, 32, 2, Duration.ofSeconds(10), 2, 50, .5,
                .70, Duration.ofSeconds(30), "memoryos-test", 0, "", "");
        var mapper = new ObjectMapper();
        var documents = mock(DocumentChunkPort.class);
        var model = mock(EmbeddingModel.class);
        when(model.call(any())).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0);
            var values = new ArrayList<Embedding>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                float[] vector = new float[3072];
                String text = request.getInstructions().get(i);
                vector[text.contains("vacation") || text.contains("nghỉ") || text.contains("leave") ? 0 : 1] = 1;
                values.add(new Embedding(vector, i));
            }
            return new EmbeddingResponse(values, new EmbeddingResponseMetadata("text-embedding-3-large", new EmptyUsage()));
        });
        try (var transport = config.searchTransport(properties)) {
            var gateway = org.mockito.Mockito.spy(new OpenSearchGateway(config.searchClient(transport), mapper));
            var sourceSearch = mock(io.memoryos.connector.SourceSearchService.class);
            var index = new OpenSearchIndexService(gateway, generations(properties, new ValidatedEmbeddingService(model, properties.model(), 3072, 32, 2)),
                    properties, mapper, documents, sourceSearch,
                    new io.memoryos.retrieval.SearchTimings(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), io.micrometer.observation.ObservationRegistry.NOOP));
            var tenant = new TenantId(UUID.randomUUID());
            var actor = new ActorId(UUID.randomUUID());
            var leave = document(tenant, "HR-2026 Nghỉ phép", "Annual vacation policy provides 12 leave days.");
            var unrelated = document(tenant, "IT-2026", "Hardware inventory and laptop replacement.");
            var privateText = document(tenant, "Private HR-2026", "Annual vacation policy provides private leave days.");
            var privateFile = new DocumentChunkSet(tenant,privateText.documentId(),privateText.generation(),privateText.title(),
                    privateText.mediaType(),privateText.updatedAt(),privateText.chunks(),UUID.randomUUID());
            UUID fileSource = UUID.randomUUID(), driveSource = UUID.randomUUID();
            var uploaded = new DocumentSourceMetadata(fileSource, UUID.randomUUID(), SourceType.FILE,
                    Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-09-07T00:00:00Z"), List.of("Alice"));
            var remote = new DocumentSourceMetadata(driveSource, UUID.randomUUID(), SourceType.GOOGLE_DRIVE,
                    Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z"), List.of("Bob"));
            var origins = new java.util.concurrent.atomic.AtomicReference<>(List.of(uploaded, remote));
            when(sourceSearch.indexMetadata(any(), any(), any())).thenAnswer(call ->
                    leave.documentId().equals(call.getArgument(1)) ? origins.get() : List.of());
            var accessOf = new java.util.concurrent.ConcurrentHashMap<DocumentId, DocumentAccess>();
            when(sourceSearch.indexAccess(any(), any(DocumentId.class))).thenAnswer(call ->
                    accessOf.getOrDefault(call.<DocumentId>getArgument(1), new DocumentAccess(true, Set.of())));
            io.memoryos.connector.SourceSearchMocks.answerPagesFromSingleDocuments(sourceSearch);
            // Before the first write the index does not exist: searches find nothing and a document window is unavailable.
            assertTrue(index.search(tenant, "vacation policy", List.of(), null, Set.of()).isEmpty());
            assertThrows(io.memoryos.retrieval.SearchDocumentUnavailableException.class,
                    () -> index.document(tenant, leave.documentId().value(), leave.generation(), 0, 5));
            verify(gateway, org.mockito.Mockito.never()).exists(any());
            index.index(leave);
            index.index(unrelated);
            index.index(privateFile);
            // The index was verified by the first write; later writes send only the vector lookup, the bulk and the count.
            clearInvocations(gateway);
            index.index(unrelated);
            verify(gateway, org.mockito.Mockito.never()).exists(any());
            verify(gateway, org.mockito.Mockito.never()).json(any(), any(), any(), any());
            assertEquals(3, org.mockito.Mockito.mockingDetails(gateway).getInvocations().size());
            assertTrue(index.contains(new DocumentIndexState(tenant,privateFile.documentId(),privateFile.generation(),1,true)));
            assertTrue(index.search(tenant,"vacation policy",List.of(), null, Set.of()).stream()
                    .noneMatch(hit -> hit.documentId().equals(privateFile.documentId().value())),
                    "Private files must be excluded before lexical/vector candidate ranking, even without a source filter");
            // Document access: a restricted document matches only a shared Group token, and an access refresh
            // rewrites the chunks in place without embedding while invalidating the previous metadata hash.
            String group = DocumentAccess.group(UUID.randomUUID());
            var restricted = document(tenant, "Restricted HR-2026", "Confidential vacation policy for managers.");
            accessOf.put(restricted.documentId(), new DocumentAccess(false, Set.of(group)));
            index.index(restricted);
            assertTrue(index.search(tenant, "confidential managers", List.of(), null, Set.of()).stream()
                    .noneMatch(hit -> hit.documentId().equals(restricted.documentId().value())), "No shared token must exclude the document in the index");
            assertTrue(index.search(tenant, "confidential managers", List.of(), null, Set.of(group)).stream()
                    .anyMatch(hit -> hit.documentId().equals(restricted.documentId().value())));
            var restrictedState = new DocumentIndexState(tenant, restricted.documentId(), restricted.generation(), 1, true);
            assertTrue(index.contains(restrictedState));
            accessOf.put(restricted.documentId(), new DocumentAccess(false, Set.of()));
            assertFalse(index.contains(restrictedState), "An access change must make the projection stale");
            assertTrue(index.containsGeneration(restrictedState), "Stale access alone leaves the complete generation indexed");
            // Reconcile reads a whole page at once: one metadata and one access read per Tenant, one aggregation, no HEAD.
            // Per document it used to cost two metadata/access reads, two HEADs and two _count requests.
            var leaveReady = new DocumentIndexState(tenant, leave.documentId(), leave.generation(), 1, true);
            var neverIndexed = new DocumentIndexState(tenant, new DocumentId(UUID.randomUUID()), UUID.randomUUID(), 1, true);
            var shorter = new DocumentIndexState(tenant, unrelated.documentId(), unrelated.generation(), 2, true);
            clearInvocations(gateway, sourceSearch);
            assertEquals(Map.of(leave.documentId(), io.memoryos.retrieval.SearchIndex.Projection.CURRENT,
                    restricted.documentId(), io.memoryos.retrieval.SearchIndex.Projection.STALE_FIELDS,
                    neverIndexed.documentId(), io.memoryos.retrieval.SearchIndex.Projection.INCOMPLETE,
                    unrelated.documentId(), io.memoryos.retrieval.SearchIndex.Projection.INCOMPLETE),
                    index.inspect(List.of(leaveReady, restrictedState, neverIndexed, shorter), index.identity()));
            assertEquals(1, org.mockito.Mockito.mockingDetails(gateway).getInvocations().size());
            verify(gateway, org.mockito.Mockito.never()).exists(any());
            verify(sourceSearch).indexMetadata(org.mockito.ArgumentMatchers.eq(tenant), org.mockito.ArgumentMatchers.anyMap());
            verify(sourceSearch).indexAccess(org.mockito.ArgumentMatchers.eq(tenant), org.mockito.ArgumentMatchers.anyCollection());
            clearInvocations(model);
            index.updateAccess(tenant, restricted.documentId(), restricted.generation());
            verifyNoInteractions(model);
            assertTrue(index.contains(restrictedState));
            assertTrue(index.search(tenant, "confidential managers", List.of(), null, Set.of(group)).stream()
                    .noneMatch(hit -> hit.documentId().equals(restricted.documentId().value())), "A revoked Group token must stop matching after refresh");
            gateway.json("POST", "/" + index.identity() + "/_update/" + restricted.chunkId(0), Map.of("refresh", "true"),
                    Map.of("script", Map.of("source", "ctx._source.remove('access_public'); ctx._source.remove('access_control_list')")));
            assertTrue(index.search(tenant, "confidential managers", List.of(), null, Set.of()).stream()
                    .anyMatch(hit -> hit.documentId().equals(restricted.documentId().value())),
                    "Chunks written before access fields existed stay visible until backfill; the database recheck authorizes hits");
            index.delete(tenant, restricted.documentId());
            String collision = index.identity() + "-collision";
            gateway.json("PUT", "/" + collision, Map.of(),
                    Map.of("aliases", Map.of(index.identity() + "-read", Map.of())));
            assertThrows(SearchUnavailableException.class, index::ensureIndex);
            gateway.json("DELETE", "/" + collision, Map.of(), null);
            index.ensureIndex();
            var foreign = document(new TenantId(UUID.randomUUID()), "HR-2026", "secret vacation policy");
            index.index(foreign);
            var leaveState = new DocumentIndexState(tenant, leave.documentId(), leave.generation(), 1, true);
            var unrelatedState = new DocumentIndexState(tenant, unrelated.documentId(), unrelated.generation(), 1, true);
            clearInvocations(model);
            index.index(leave);
            verifyNoInteractions(model);
            assertTrue(index.contains(leaveState));
            var scope = new SourceSearchScope(tenant, actor, Map.of(fileSource, SourceType.FILE, driveSource, SourceType.GOOGLE_DRIVE));
            var september = new SearchFilters(java.util.Set.of(SourceType.FILE),
                    new SearchFilters.Interval(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-31T23:59:59Z")),
                    new SearchFilters.Interval(null, Instant.parse("2026-09-08T00:00:00Z")));
            var queries = List.of(new SearchQuery("HR-2026", false, .7), new SearchQuery("HR-2026", true, 1), new SearchQuery("nghỉ", false, 1.3));
            clearInvocations(model, gateway);
            var batch = index.batch(scope, queries, september, () -> {});
            assertEquals(3, batch.size());
            assertTrue(batch.stream().allMatch(h -> h.size() == 1 && h.getFirst().documentId().equals(leave.documentId().value())));
            var embedded = org.mockito.ArgumentCaptor.forClass(EmbeddingRequest.class);
            verify(model).call(embedded.capture());
            assertEquals(List.of("HR-2026", "nghỉ"), embedded.getValue().getInstructions());
            verify(gateway, org.mockito.Mockito.never()).exists(any());
            // One hybrid request per distinct text and no existence check before it.
            verify(gateway, org.mockito.Mockito.times(2)).jsonOrMissing(org.mockito.ArgumentMatchers.eq("POST"),
                    org.mockito.ArgumentMatchers.eq("/" + index.identity() + "-read/_search"), any(), any());
            var wrongSourceDate = new SearchFilters(java.util.Set.of(SourceType.FILE), null,
                    new SearchFilters.Interval(Instant.parse("2026-09-09T00:00:00Z"), Instant.parse("2026-09-11T00:00:00Z")));
            assertTrue(index.batch(scope, queries, wrongSourceDate, () -> {}).stream().allMatch(List::isEmpty),
                    "A date on one mapping must not be combined with another mapping's source type; lexical and vector branches both filter");
            var fileOnly = new SourceSearchScope(tenant, actor, Map.of(fileSource, SourceType.FILE));
            assertTrue(index.batch(fileOnly, queries, new SearchFilters(java.util.Set.of(), null, wrongSourceDate.updated()), () -> {})
                    .stream().allMatch(List::isEmpty), "Inaccessible origins cannot satisfy a time filter");

            gateway.json("POST", "/" + index.identity() + "/_update/" + leave.chunkId(0), Map.of("refresh", "true"),
                    Map.of("script", Map.of("source", "ctx._source.remove('metadata_hash')")));
            assertFalse(index.contains(leaveState), "Equal chunk counts do not make legacy metadata ready");
            clearInvocations(model);
            index.index(leave);
            verifyNoInteractions(model);
            assertTrue(index.contains(leaveState));
            origins.set(List.of());
            index.index(leave);
            var driveOnly = new SourceSearchScope(tenant, actor, Map.of(driveSource, SourceType.GOOGLE_DRIVE));
            assertTrue(index.batch(driveOnly, queries, SearchFilters.NONE, () -> {}).stream().allMatch(List::isEmpty));
            origins.set(List.of(remote));
            assertFalse(index.contains(leaveState), "Previously indexed Drive chunks require their newly eligible Source metadata");
            clearInvocations(model);
            index.index(leave);
            verifyNoInteractions(model);
            assertTrue(index.contains(leaveState));
            assertTrue(index.batch(driveOnly, queries, SearchFilters.NONE, () -> {}).stream()
                    .allMatch(h -> h.size() == 1 && h.getFirst().documentId().equals(leave.documentId().value())));
            clearInvocations(model);
            origins.set(List.of(new DocumentSourceMetadata(uploaded.sourceId(), uploaded.itemId(), uploaded.type(),
                    uploaded.createdAt(), Instant.parse("2026-09-15T00:00:00Z"), uploaded.authors()), remote));
            assertFalse(index.contains(leaveState));
            index.index(leave);
            verifyNoInteractions(model);
            assertTrue(index.contains(leaveState));
            var hits = index.search(tenant, "quy định nghỉ phép", List.of(), null, Set.of());
            assertEquals(1, hits.size());
            assertEquals(leave.documentId().value(), hits.getFirst().documentId());
            assertTrue(hits.stream().noneMatch(h -> h.documentId().equals(unrelated.documentId().value())));
            assertTrue(hits.stream().noneMatch(h -> h.documentId().equals(foreign.documentId().value())));
            assertTrue(index.search(tenant, "HR-2026", List.of("application/pdf"), null, Set.of()).isEmpty());
            var replacement = new DocumentChunkSet(tenant, leave.documentId(), UUID.randomUUID(), leave.title(), leave.mediaType(), Instant.now(), leave.chunks());
            clearInvocations(model);
            var replacementState = new DocumentIndexState(tenant, replacement.documentId(), replacement.generation(), 1, true);
            index.index(replacement);
            verifyNoInteractions(model);
            // While the replacement is pending the served generation is retained by both cleanup paths.
            var retained = new java.util.concurrent.atomic.AtomicReference<>(Set.of(leave.generation(), replacement.generation()));
            when(documents.retainedGenerations(any(), any())).thenAnswer(invocation -> {
                TenantId requested = invocation.getArgument(0);
                return requested.equals(tenant) ? Map.of(leave.documentId().value(), retained.get(), unrelated.documentId().value(), Set.of(unrelated.generation()))
                        : Map.of(foreign.documentId().value(), Set.of(foreign.generation()));
            });
            index.purgeStale();
            index.purgeObsolete(tenant, leave.documentId());
            assertTrue(index.contains(leaveState));
            assertTrue(index.contains(replacementState));
            retained.set(Set.of(replacement.generation()));
            index.purgeObsolete(tenant, leave.documentId());
            assertFalse(index.contains(leaveState));
            assertTrue(index.contains(replacementState));
            assertTrue(index.contains(unrelatedState));
            retained.set(Set.of(leave.generation()));
            index.purgeStale();
            assertFalse(index.contains(replacementState), "The sweep removes a generation that is neither served nor current");
            index.index(replacement);
            retained.set(Set.of(replacement.generation()));
            index.delete(tenant, leave.documentId());
            assertFalse(index.contains(replacementState));
            // A missing physical index is rebuilt using the same real write path.
            gateway.json("DELETE", "/" + index.identity(), Map.of(), null);
            assertFalse(index.contains(unrelatedState));
            // The write finds the verified index gone, forgets the verification, creates and verifies it once, and retries.
            clearInvocations(gateway);
            index.index(unrelated);
            verify(gateway).json(org.mockito.ArgumentMatchers.eq("PUT"), org.mockito.ArgumentMatchers.eq("/" + index.identity()), any(), any());
            verify(gateway).json(org.mockito.ArgumentMatchers.eq("GET"), org.mockito.ArgumentMatchers.eq("/" + index.identity() + "/_mapping"), any(), any());
            assertTrue(index.contains(unrelatedState));
            var chunks = java.util.stream.IntStream.range(0, 25).mapToObj(i -> {
                String text = "vacation policy section " + i;
                return new DocumentChunk(i, text, List.of(), i, 0, "[{\"page\":" + i + "}]",
                        StructuredDocumentChunker.sha256(text), 10);
            }).toList();
            var paged = new DocumentChunkSet(tenant, new DocumentId(UUID.randomUUID()), UUID.randomUUID(),
                    "Paged HR", "text/plain", Instant.now(), chunks);
            index.index(paged);
            clearInvocations(model);
            var page = index.document(tenant, paged.documentId().value(), paged.generation(), 18, 5);
            assertEquals(25, page.totalChunks());
            assertEquals(List.of(18, 19, 20, 21, 22), page.passages().stream().map(io.memoryos.retrieval.SearchPage.Passage::ordinal).toList());
            assertTrue(page.hasMore());
            assertEquals("[{\"page\":18}]", page.passages().getFirst().provenanceJson());
            assertEquals(2, index.document(tenant, paged.documentId().value(), paged.generation(), 23, 20).passages().size());
            var end = index.document(tenant, paged.documentId().value(), paged.generation(), 99, 20);
            assertEquals(25, end.firstOrdinal());
            assertEquals("Paged HR", end.title());
            assertTrue(end.passages().isEmpty());
            assertFalse(end.hasMore());
            assertThrows(io.memoryos.retrieval.SearchDocumentUnavailableException.class,
                    () -> index.document(foreign.tenantId(), paged.documentId().value(), paged.generation(), 0, 5));
            assertThrows(io.memoryos.retrieval.SearchDocumentUnavailableException.class,
                    () -> index.document(tenant, paged.documentId().value(), UUID.randomUUID(), 0, 5));
            verifyNoInteractions(model);
            // Short keyword queries use the same hybrid path: neither lexical-only nor semantic-only hits disappear.
            var keywordHits = index.search(tenant, "Paged HR", List.of(), null, Set.of());
            assertTrue(keywordHits.stream().anyMatch(hit -> hit.documentId().equals(paged.documentId().value())));
            assertTrue(keywordHits.stream().anyMatch(hit -> hit.documentId().equals(unrelated.documentId().value())));
            verify(model).call(any());

            // More chunks than one query batch: access refresh and delete work by chunk ID and never issue
            // *_by_query requests, whose continuation needs scroll permissions the service role lacks.
            var largeChunks = java.util.stream.IntStream.range(0, 1100).mapToObj(i -> {
                String text = "large vacation section " + i;
                return new DocumentChunk(i, text, List.of(), i, 0, "[]", StructuredDocumentChunker.sha256(text), 10);
            }).toList();
            var large = new DocumentChunkSet(tenant, new DocumentId(UUID.randomUUID()), UUID.randomUUID(), "Large HR", "text/plain", Instant.now(), largeChunks);
            index.index(large);
            var largeState = new DocumentIndexState(tenant, large.documentId(), large.generation(), largeChunks.size(), true);
            assertTrue(index.contains(largeState));
            accessOf.put(large.documentId(), new DocumentAccess(false, Set.of(group)));
            assertFalse(index.contains(largeState));
            clearInvocations(model);
            index.updateAccess(tenant, large.documentId(), large.generation());
            verifyNoInteractions(model);
            assertTrue(index.contains(largeState), "Every chunk beyond the first batch must receive the refreshed access fields");
            index.delete(tenant, large.documentId());
            assertEquals(0, gateway.json("POST", "/" + index.identity() + "/_count", Map.of(),
                    Map.of("query", Map.of("term", Map.of("document_id", large.documentId().value().toString())))).path("count").asInt(-1));
            verify(gateway, org.mockito.Mockito.never()).json(any(), org.mockito.ArgumentMatchers.contains("_by_query"), any(), any());
        }
    }

    @Test
    void anIndexThatDisagreesWithItsGenerationFailsLoudlyAndAnIndexFromBeforeGenerationsIsRecordedOnce() throws Exception {
        var config = new SearchInfrastructureConfiguration();
        var properties = new SearchProperties(new URI("http", null, OPENSEARCH.getHost(), OPENSEARCH.getMappedPort(9200), null, null, null),
                "", "", "", "https://api.openai.com/v1", "", "text-embedding-3-small", 8, 32, 2, Duration.ofSeconds(10), 2, 50, .5,
                .70, Duration.ofSeconds(30), "memoryos-meta", 0, "", "");
        var mapper = new ObjectMapper();
        var embeddings = new ValidatedEmbeddingService(mock(EmbeddingModel.class), properties.model(), 8, 32, 2);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OpenSearchIndexService.class);
        events.start();
        logger.addAppender(events);
        try (var transport = config.searchTransport(properties)) {
            var gateway = new OpenSearchGateway(config.searchClient(transport), mapper);
            // The index a deployment created before generations: _meta names only its identity and model.
            String legacy = SearchGenerations.legacyIdentity(properties);
            gateway.json("PUT", "/" + legacy, Map.of(), Map.of("settings", Map.of("index.knn", true, "number_of_replicas", 0),
                    "mappings", Map.of("dynamic", "strict", "_meta", Map.of("identity", legacy, "model", "text-embedding-3-small"),
                            "properties", Map.of("tenant_id", Map.of("type", "keyword"), "vector", Map.of("type", "knn_vector", "dimension", 8,
                                    "method", Map.of("name", "hnsw", "engine", "faiss", "space_type", "cosinesimil"))))));
            var seeded = generation(properties, legacy, "text-embedding-3-small", 8, "");
            var index = new OpenSearchIndexService(gateway, SearchGenerations.fixed(seeded, embeddings, properties), properties, mapper,
                    mock(DocumentChunkPort.class), mock(io.memoryos.connector.SourceSearchService.class), timings());
            index.verifyOnStartup();
            var meta = gateway.json("GET", "/" + legacy + "/_mapping", Map.of(), null).path(legacy).path("mappings").path("_meta");
            assertEquals(legacy, meta.path("identity").asString());
            assertEquals(seeded.id().toString(), meta.path("generation").asString());
            assertEquals(8, meta.path("dimensions").asInt());
            assertEquals("", meta.path("document_prefix").asString());
            assertEquals(DocumentChunk.CONVENTION, meta.path("chunk_convention").asString());
            assertTrue(logged(events, "search.index.generation_recorded"));

            // Same index, other vectors: a document prefix, a model or a dimension that disagrees stops search and indexing.
            for (var wrong : List.of(generation(properties, legacy, "text-embedding-3-small", 8, "passage: "),
                    generation(properties, legacy, "Qwen/Qwen3-Embedding-0.6B", 8, ""),
                    generation(properties, legacy, "text-embedding-3-small", 16, ""))) {
                var mismatched = new OpenSearchIndexService(gateway, SearchGenerations.fixed(wrong, embeddings, properties), properties,
                        mapper, mock(DocumentChunkPort.class), mock(io.memoryos.connector.SourceSearchService.class), timings());
                events.list.clear();
                assertThrows(SearchUnavailableException.class, mismatched::ensureIndex);
                assertTrue(logged(events, "search.index.generation_mismatch"), wrong.toString());
                events.list.clear();
                mismatched.verifyOnStartup(); // reported, not thrown: the process keeps running and keeps refusing
                assertTrue(logged(events, "search.index.generation_mismatch"), wrong.toString());
            }

            // A generation created after seeding names its index by its own ID and records everything that decides vectors.
            var id = UUID.randomUUID();
            var future = new io.memoryos.retrieval.settings.SearchGeneration(id, UUID.randomUUID(), UUID.randomUUID(),
                    "Qwen/Qwen3-Embedding-0.6B", 8, "Instruct: Given a question, retrieve passages that answer it\nQuery: ", "", .7,
                    DocumentChunk.CONVENTION, io.memoryos.retrieval.settings.SearchGeneration.identityFor("memoryos-meta", id),
                    io.memoryos.retrieval.settings.SearchGeneration.Status.FUTURE, false, Instant.now(), null, null);
            new OpenSearchIndexService(gateway, SearchGenerations.fixed(future, embeddings, properties), properties, mapper,
                    mock(DocumentChunkPort.class), mock(io.memoryos.connector.SourceSearchService.class), timings()).ensureIndex();
            assertEquals("memoryos-meta-" + id, future.identity());
            var created = gateway.json("GET", "/" + future.identity() + "/_mapping", Map.of(), null).path(future.identity())
                    .path("mappings").path("_meta");
            assertEquals("Qwen/Qwen3-Embedding-0.6B", created.path("model").asString());
            assertEquals(8, created.path("dimensions").asInt());
            assertEquals(DocumentChunk.CONVENTION, created.path("chunk_convention").asString());
        } finally {
            logger.detachAppender(events);
        }
    }

    private static boolean logged(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events, String name) {
        return events.list.stream().anyMatch(event -> event.getKeyValuePairs() != null && event.getKeyValuePairs().stream()
                .anyMatch(pair -> "event".equals(pair.key) && name.equals(pair.value)));
    }

    private static io.memoryos.retrieval.settings.SearchGeneration generation(SearchProperties properties, String identity,
            String model, int dimensions, String documentPrefix) {
        return new io.memoryos.retrieval.settings.SearchGeneration(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), model,
                dimensions, "", documentPrefix, properties.minimumSemanticScore(), DocumentChunk.CONVENTION, identity,
                io.memoryos.retrieval.settings.SearchGeneration.Status.PRESENT, false, Instant.now(), Instant.now(), null);
    }

    private static SearchGenerations generations(SearchProperties properties, ValidatedEmbeddingService embeddings) {
        return SearchGenerations.fixed(generation(properties, SearchGenerations.legacyIdentity(properties), properties.model(),
                properties.dimensions(), ""), embeddings, properties);
    }

    private static io.memoryos.retrieval.SearchTimings timings() {
        return new io.memoryos.retrieval.SearchTimings(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                io.micrometer.observation.ObservationRegistry.NOOP);
    }

    private DocumentChunkSet document(TenantId tenant, String title, String text) {
        return new DocumentChunkSet(tenant, new DocumentId(UUID.randomUUID()), UUID.randomUUID(), title, "text/plain", Instant.now(),
                List.of(new DocumentChunk(0, text, List.of(), 0, 0, "[]",
                        StructuredDocumentChunker.sha256(text), 30)));
    }
}
