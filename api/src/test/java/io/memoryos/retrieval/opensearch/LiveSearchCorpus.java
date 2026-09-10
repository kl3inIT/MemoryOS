package io.memoryos.retrieval.opensearch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.memoryos.connector.DocumentSourceMetadata;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceType;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.iam.TenantId;
import io.memoryos.retrieval.SearchTimings;
import io.memoryos.retrieval.embedding.ValidatedEmbeddingService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.opensearch.client.transport.OpenSearchTransport;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import tools.jackson.databind.ObjectMapper;

/** Opt-in acceptance fixture: an authorized snapshot, isolated index, real query embeddings.
 * No corpus, credentials or answers are checked into the repository. */
public final class LiveSearchCorpus implements AutoCloseable {
    private final GenericContainer<?> container;
    private final OpenSearchTransport transport;
    public final OpenSearchIndexService index;

    @SuppressWarnings("resource") // This fixture owns both resources through close, including failed setup.
    public LiveSearchCorpus(Path snapshot, String key, TenantId tenant, DocumentChunkPort chunks,
            SourceSearchService sources, MeterRegistry meters) throws Exception {
        container = new GenericContainer<>("opensearchproject/opensearch:3.8.0@sha256:bcc1797519726ceb6d651d4a3e60b7c30da91793914a8dfe75fd441d4f641509")
                .withEnv("discovery.type", "single-node").withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true").withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withExposedPorts(9200).waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(3)));
        container.start();
        var config = new SearchInfrastructureConfiguration();
        var properties = new SearchProperties(URI.create("http://" + container.getHost() + ":" + container.getMappedPort(9200)),
                "", "", "", "https://api.openai.com/v1", key, "text-embedding-3-large", 3072, 32, 2, 500, .5,
                .70, Duration.ofSeconds(30), "memoryos-acceptance", 0);
        transport = config.searchTransport(properties);
        try {
            var mapper = new ObjectMapper();
            var data = mapper.readTree(snapshot.toFile());
            var origins = new LinkedHashMap<UUID, List<DocumentSourceMetadata>>();
            var generations = new LinkedHashMap<UUID, UUID>();
            var scope = new LinkedHashMap<UUID, SourceType>();
            for (var row : data.path("origins")) {
                UUID doc = UUID.fromString(row.path("document_id").asString());
                UUID source = UUID.fromString(row.path("source_id").asString());
                scope.put(source, SourceType.FILE);
                generations.put(doc, UUID.fromString(row.path("generation").asString()));
                origins.computeIfAbsent(doc, _ -> new ArrayList<>()).add(new DocumentSourceMetadata(source,
                        UUID.fromString(row.path("item_id").asString()), SourceType.FILE,
                        Instant.parse(row.path("created_at").asString()), Instant.parse(row.path("updated_at").asString()), List.of()));
            }
            when(sources.scope(any())).thenReturn(new SourceSearchScope(tenant, scope));
            when(sources.readableMetadata(any(), any())).thenReturn(origins);
            when(chunks.currentGenerations(any(), any(), any())).thenReturn(generations);
            when(chunks.isCurrent(any(), any(), any(), any())).thenReturn(true);
            var gateway = new OpenSearchGateway(config.searchClient(transport), mapper);
            index = new OpenSearchIndexService(gateway, new ValidatedEmbeddingService(
                    config.searchEmbeddingModel(properties, ObservationRegistry.NOOP), properties.model(), 3072, 32, 2),
                    properties, mapper, chunks, sources, new SearchTimings(meters, ObservationRegistry.NOOP));
            index.ensureIndex();
            var bulk = new StringBuilder();
            for (var hit : data.path("hits")) {
                var value = (tools.jackson.databind.node.ObjectNode) hit.path("_source");
                value.put("tenant_id", tenant.value().toString()).put("index_identity", index.identity());
                UUID doc = UUID.fromString(value.path("document_id").asString());
                if (!generations.get(doc).toString().equals(value.path("generation").asString()))
                    throw new IllegalArgumentException("Snapshot contains an obsolete generation");
                value.set("source_metadata", mapper.valueToTree(origins.get(doc).stream().map(origin -> Map.of(
                        "source_id", origin.sourceId().toString(), "item_id", origin.itemId().toString(), "type", "FILE",
                        "created_at", java.util.Objects.requireNonNull(origin.createdAt()).toString(),
                        "updated_at", java.util.Objects.requireNonNull(origin.updatedAt()).toString())).toList()));
                bulk.append(mapper.writeValueAsString(Map.of("index", Map.of("_index", index.identity(), "_id", hit.path("_id").asString())))).append('\n');
                bulk.append(mapper.writeValueAsString(value)).append('\n');
            }
            if (gateway.bulk("/_bulk", bulk.toString()).path("errors").asBoolean())
                throw new IllegalStateException("Unable to seed acceptance corpus");
        } catch (Exception | Error failure) {
            close();
            throw failure;
        }
    }

    @Override public void close() throws Exception {
        try { transport.close(); }
        finally { container.close(); }
    }
}
