package io.memoryos.retrieval.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceType;
import io.memoryos.document.DocumentChunk;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.retrieval.SearchFilters;
import io.memoryos.retrieval.SearchQuery;
import io.memoryos.retrieval.SearchTimings;
import io.memoryos.retrieval.embedding.ValidatedEmbeddingService;
import io.memoryos.retrieval.settings.SearchGeneration;
import io.memoryos.retrieval.settings.SearchGenerations;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import tools.jackson.databind.ObjectMapper;

/** A question asked again under the same generation, by the same reader, is not embedded again. */
class QueryVectorReuseTest {
    private static final String MODEL = "test-embedding";
    private final ObjectMapper mapper = new ObjectMapper();
    private final SearchProperties properties = new SearchProperties(URI.create("http://127.0.0.1:9200"),
            "", "", "", "http://embeddings.internal/v1", MODEL, 8, 32, 2, Duration.ofSeconds(10), 2, 50, .5,
            .70, Duration.ofSeconds(30), "memoryos-test", 0, "", "");
    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private final ValidatedEmbeddingService embeddings = new ValidatedEmbeddingService(model, MODEL, 8, 32, 2);
    private final OpenSearchGateway gateway = mock(OpenSearchGateway.class);
    private final SearchGenerations generations = mock(SearchGenerations.class);
    private final OpenSearchIndexService index = new OpenSearchIndexService(gateway, generations, properties, mapper,
            mock(DocumentChunkPort.class), mock(SourceSearchService.class),
            new SearchTimings(new SimpleMeterRegistry(), ObservationRegistry.NOOP));
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final Set<String> tokens = Set.of("group:hr");

    QueryVectorReuseTest() {
        when(model.call(any())).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0);
            var values = new ArrayList<Embedding>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                float[] vector = new float[8];
                vector[Math.floorMod(request.getInstructions().get(i).hashCode(), 8)] = 1;
                values.add(new Embedding(vector, i));
            }
            return new EmbeddingResponse(values, new EmbeddingResponseMetadata(MODEL, new EmptyUsage()));
        });
        when(gateway.jsonOrMissing(any(), any(), any(), any())).thenReturn(mapper.readTree("{\"missing\":true}"));
        when(generations.present()).thenReturn(active("memoryos-test-a"));
    }

    @Test
    void anotherPageOrFilterOfTheSameQuestionCallsTheEmbedderOnceAndSendsTheSameIndexQuery() {
        index.search(tenant, actor, "nghỉ phép", List.of(), null, tokens);
        index.search(tenant, actor, "nghỉ phép", List.of(), null, tokens);
        index.search(tenant, actor, "nghỉ phép", List.of("application/pdf"),
                new SearchFilters.Interval(Instant.parse("2026-09-01T00:00:00Z"), null), tokens);

        verify(model, times(1)).call(any());
        ArgumentCaptor<Object> bodies = ArgumentCaptor.forClass(Object.class);
        verify(gateway, times(3)).jsonOrMissing(eq("POST"), any(), any(), bodies.capture());
        // The same request reaches the index whether the vector was embedded or kept.
        assertEquals(mapper.writeValueAsString(bodies.getAllValues().get(0)), mapper.writeValueAsString(bodies.getAllValues().get(1)));
    }

    @Test
    void anotherGenerationAnotherTextOrAnotherReaderCallsTheEmbedderAgain() {
        index.search(tenant, actor, "nghỉ phép", List.of(), null, tokens);
        when(generations.present()).thenReturn(active("memoryos-test-b"));
        index.search(tenant, actor, "nghỉ phép", List.of(), null, tokens);
        verify(model, times(2)).call(any());

        index.search(tenant, actor, "lương", List.of(), null, tokens);
        verify(model, times(3)).call(any());

        index.search(tenant, new ActorId(UUID.randomUUID()), "lương", List.of(), null, tokens);
        verify(model, times(4)).call(any());
    }

    @Test
    void chatSearchEmbedsOnlyTheTextsWithoutAKeptVectorAndSharesThemWithDirectSearch() {
        var scope = new SourceSearchScope(tenant, actor, Map.of(UUID.randomUUID(), SourceType.FILE), tokens);
        index.batch(scope, List.of(new SearchQuery("leave", false, .7), new SearchQuery("HR", true, 1)), SearchFilters.NONE, () -> {});
        index.batch(scope, List.of(new SearchQuery("leave", false, .7), new SearchQuery("payroll", true, 1)), SearchFilters.NONE, () -> {});
        index.search(tenant, actor, "HR", List.of(), null, tokens);

        var requests = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(model, times(2)).call(requests.capture());
        assertEquals(List.of("leave", "HR"), requests.getAllValues().get(0).getInstructions());
        assertEquals(List.of("payroll"), requests.getAllValues().get(1).getInstructions());
    }

    private SearchGenerations.Active active(String identity) {
        var generation = new SearchGeneration(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), MODEL, 8, "", "",
                properties.minimumSemanticScore(), DocumentChunk.CONVENTION, identity, SearchGeneration.Status.PRESENT,
                false, Instant.now(), Instant.now(), null);
        return new SearchGenerations.Active(generation, embeddings, true);
    }
}
