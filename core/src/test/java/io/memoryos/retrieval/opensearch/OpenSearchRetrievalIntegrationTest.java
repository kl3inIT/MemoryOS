package io.memoryos.retrieval.opensearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
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

@Testcontainers(disabledWithoutDocker = true)
class OpenSearchRetrievalIntegrationTest {
    // JUnit's Testcontainers extension starts and closes this shared container.
    @Container
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>("opensearchproject/opensearch:3.8.0@sha256:bcc1797519726ceb6d651d4a3e60b7c30da91793914a8dfe75fd441d4f641509")
            .withEnv("discovery.type", "single-node").withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true").withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200).waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(3)));

    @Test
    void indexes3072DimensionsFusesKeywordAndSemanticResultsReusesVectorsAndRepairsProjection() throws Exception {
        var config = new SearchInfrastructureConfiguration();
        var properties = new SearchProperties(new URI("http", null, OPENSEARCH.getHost(), OPENSEARCH.getMappedPort(9200), null, null, null),
                "", "", "", "https://api.openai.com/v1", "", "text-embedding-3-large", 3072, 32, 2, 50, .5,
                Duration.ofSeconds(30), "memoryos-test", 0);
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
            var gateway = new OpenSearchGateway(config.searchClient(transport), mapper);
            var index = new OpenSearchIndexService(gateway, new ValidatedEmbeddingService(model, properties.model(), 3072, 32, 2), properties, mapper, documents);
            var tenant = new TenantId(UUID.randomUUID());
            var leave = document(tenant, "HR-2026 Nghỉ phép", "Annual vacation policy provides 12 leave days.");
            var unrelated = document(tenant, "IT-2026", "Hardware inventory and laptop replacement.");
            index.index(leave);
            index.index(unrelated);
            var foreign = document(new TenantId(UUID.randomUUID()), "HR-2026", "secret vacation policy");
            index.index(foreign);
            var leaveState = new DocumentIndexState(tenant, leave.documentId(), leave.generation(), 1, true);
            var unrelatedState = new DocumentIndexState(tenant, unrelated.documentId(), unrelated.generation(), 1, true);
            clearInvocations(model);
            index.index(leave);
            verifyNoInteractions(model);
            assertTrue(index.contains(leaveState));
            var hits = index.search(tenant, "quy định nghỉ phép", List.of(), null);
            assertFalse(hits.isEmpty());
            assertEquals(leave.documentId().value(), hits.getFirst().documentId());
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
        }
    }

    private DocumentChunkSet document(TenantId tenant, String title, String text) {
        return new DocumentChunkSet(tenant, new DocumentId(UUID.randomUUID()), UUID.randomUUID(), title, "text/plain", Instant.now(),
                List.of(new DocumentChunk(0, text, List.of(), 0, 0, "[]",
                        StructuredDocumentChunker.sha256(text), 30)));
    }
}
