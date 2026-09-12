package io.memoryos.retrieval.opensearch;

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
import io.memoryos.iam.TenantId;
import io.memoryos.retrieval.embedding.ValidatedEmbeddingService;
import io.memoryos.retrieval.SearchUnavailableException;
import io.memoryos.retrieval.SearchFilters;
import io.memoryos.retrieval.SearchQuery;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.DocumentSourceMetadata;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
                "", "", "", "https://api.openai.com/v1", "", "text-embedding-3-large", 3072, 32, 2, 50, .5,
                .70, Duration.ofSeconds(30), "memoryos-test", 0);
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
            var index = new OpenSearchIndexService(gateway, new ValidatedEmbeddingService(model, properties.model(), 3072, 32, 2), properties, mapper, documents, sourceSearch,
                    new io.memoryos.retrieval.SearchTimings(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), io.micrometer.observation.ObservationRegistry.NOOP));
            var tenant = new TenantId(UUID.randomUUID());
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
            index.index(leave);
            index.index(unrelated);
            index.index(privateFile);
            assertTrue(index.contains(new DocumentIndexState(tenant,privateFile.documentId(),privateFile.generation(),1,true)));
            assertTrue(index.search(tenant,"vacation policy",List.of(),null).stream()
                    .noneMatch(hit -> hit.documentId().equals(privateFile.documentId().value())),
                    "Private files must be excluded before lexical/vector candidate ranking, even without a source filter");
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
            var scope = new SourceSearchScope(tenant, Map.of(fileSource, SourceType.FILE, driveSource, SourceType.GOOGLE_DRIVE));
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
            verify(gateway).exists("/" + index.identity() + "-read");
            var wrongSourceDate = new SearchFilters(java.util.Set.of(SourceType.FILE), null,
                    new SearchFilters.Interval(Instant.parse("2026-09-09T00:00:00Z"), Instant.parse("2026-09-11T00:00:00Z")));
            assertTrue(index.batch(scope, queries, wrongSourceDate, () -> {}).stream().allMatch(List::isEmpty),
                    "A date on one mapping must not be combined with another mapping's source type; lexical and vector branches both filter");
            var fileOnly = new SourceSearchScope(tenant, Map.of(fileSource, SourceType.FILE));
            assertTrue(index.batch(fileOnly, queries, new SearchFilters(java.util.Set.of(), null, wrongSourceDate.updated()), () -> {})
                    .stream().allMatch(List::isEmpty), "Inaccessible origins cannot satisfy a time filter");

            gateway.json("POST", "/" + index.identity() + "/_update/" + leave.chunkId(0), Map.of("refresh", "true"),
                    Map.of("script", Map.of("source", "ctx._source.remove('metadata_hash')")));
            assertFalse(index.contains(leaveState), "Equal chunk counts do not make legacy metadata ready");
            clearInvocations(model);
            index.index(leave);
            verifyNoInteractions(model);
            assertTrue(index.contains(leaveState));
            origins.set(List.of(new DocumentSourceMetadata(uploaded.sourceId(), uploaded.itemId(), uploaded.type(),
                    uploaded.createdAt(), Instant.parse("2026-09-15T00:00:00Z"), uploaded.authors()), remote));
            assertFalse(index.contains(leaveState));
            index.index(leave);
            verifyNoInteractions(model);
            assertTrue(index.contains(leaveState));
            var hits = index.search(tenant, "quy định nghỉ phép", List.of(), null);
            assertEquals(1, hits.size());
            assertEquals(leave.documentId().value(), hits.getFirst().documentId());
            assertTrue(hits.stream().noneMatch(h -> h.documentId().equals(unrelated.documentId().value())));
            assertTrue(hits.stream().noneMatch(h -> h.documentId().equals(foreign.documentId().value())));
            assertTrue(index.search(tenant, "HR-2026", List.of("application/pdf"), null).isEmpty());
            var replacement = new DocumentChunkSet(tenant, leave.documentId(), UUID.randomUUID(), leave.title(), leave.mediaType(), Instant.now(), leave.chunks());
            clearInvocations(model);
            var replacementState = new DocumentIndexState(tenant, replacement.documentId(), replacement.generation(), 1, true);
            index.index(replacement);
            verifyNoInteractions(model);
            when(documents.currentGenerations(any(), any(), any())).thenAnswer(invocation -> {
                TenantId requested = invocation.getArgument(0);
                return requested.equals(tenant) ? Map.of(leave.documentId().value(), replacement.generation(), unrelated.documentId().value(), unrelated.generation())
                        : Map.of(foreign.documentId().value(), foreign.generation());
            });
            index.purgeStale();
            assertFalse(index.contains(leaveState));
            assertTrue(index.contains(replacementState));
            index.delete(tenant, leave.documentId());
            assertFalse(index.contains(replacementState));
            // A missing physical index is rebuilt using the same real write path.
            gateway.json("DELETE", "/" + index.identity(), Map.of(), null);
            assertFalse(index.contains(unrelatedState));
            index.index(unrelated);
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
            var keywordHits = index.search(tenant, "Paged HR", List.of(), null);
            assertTrue(keywordHits.stream().anyMatch(hit -> hit.documentId().equals(paged.documentId().value())));
            assertTrue(keywordHits.stream().anyMatch(hit -> hit.documentId().equals(unrelated.documentId().value())));
            verify(model).call(any());
        }
    }

    private DocumentChunkSet document(TenantId tenant, String title, String text) {
        return new DocumentChunkSet(tenant, new DocumentId(UUID.randomUUID()), UUID.randomUUID(), title, "text/plain", Instant.now(),
                List.of(new DocumentChunk(0, text, List.of(), 0, 0, "[]",
                        StructuredDocumentChunker.sha256(text), 30)));
    }
}
