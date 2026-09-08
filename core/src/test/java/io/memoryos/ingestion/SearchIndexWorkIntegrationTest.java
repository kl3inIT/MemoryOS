package io.memoryos.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.TestDatabase;
import io.memoryos.document.DocumentChanged;
import io.memoryos.document.DocumentChunk;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.DocumentId;
import io.memoryos.document.application.DocumentChunkService;
import io.memoryos.document.application.StructuredDocumentChunker;
import io.memoryos.document.persistence.JdbcDocumentChunkRepository;
import io.memoryos.document.persistence.JdbcDocumentRepository;
import io.memoryos.document.persistence.JdbcExtractionArtifactRepository;
import io.memoryos.iam.TenantId;
import io.memoryos.ingestion.application.SearchIngestionCoordinator;
import io.memoryos.ingestion.persistence.JdbcOperationDispatchRepository;
import io.memoryos.ingestion.persistence.JdbcSearchWorkRepository;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.retrieval.SearchIndex;
import io.memoryos.retrieval.SearchUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

// SQL is exercised against the isolated, migrated Testcontainers database.
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
@Testcontainers
class SearchIndexWorkIntegrationTest {
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private JdbcSearchWorkRepository work;
    private JdbcOperationDispatchRepository dispatch;
    private JdbcDocumentRepository documents;
    private JdbcExtractionArtifactRepository artifacts;
    private DocumentChunkService chunks;
    private JdbcDocumentChunkRepository chunkRepository;
    private TenantId tenant;
    private final SearchIndex index = mock(SearchIndex.class);
    private static final String IDENTITY = "memoryos-test-model-space";
    private static final String JSON = """
            {"schema":"memoryos-extraction-v1","blocks":[
            {"index":0,"kind":"HEADING","headingLevel":1,"text":"Nghỉ phép"},
            {"index":1,"kind":"PARAGRAPH","text":"Nhân viên có 12 ngày nghỉ phép.","provenance":[{"page_no":1}]}]}
            """;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        work = new JdbcSearchWorkRepository(jdbc);
        dispatch = new JdbcOperationDispatchRepository(jdbc);
        documents = new JdbcDocumentRepository(jdbc, new ObjectMapper(), event -> work.enqueue((DocumentChanged) event, IDENTITY, false));
        artifacts = new JdbcExtractionArtifactRepository(jdbc);
        var storage = mock(ObjectStorage.class);
        when(storage.open(any())).thenAnswer(_ -> new ObjectContent() {
            @Override public ObjectMetadata metadata() { throw new UnsupportedOperationException(); }
            @Override public InputStream inputStream() { return new ByteArrayInputStream(JSON.getBytes(StandardCharsets.UTF_8)); }
            @Override public void close() { }
        });
        chunkRepository = new JdbcDocumentChunkRepository(jdbc, new ObjectMapper());
        chunks = new DocumentChunkService(chunkRepository, storage, new StructuredDocumentChunker(new ObjectMapper()));
        tenant = new TenantId(UUID.randomUUID());
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'search-test','Search','ACTIVE','MEM-46')")
                .param("id", tenant.value()).update();
        when(index.identity()).thenReturn(IDENTITY);
    }

    @Test
    void commitPublishesDurableIntentAndWorkerOnlyMarksReadyAfterIndexSuccess() {
        DocumentId document = publish(null);
        assertEquals(1, count("search_index_operations"));
        assertEquals(0, count("document_chunks"));
        var delivery = delivery();
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var metrics = new SimpleMeterRegistry();
            var coordinator = new SearchIngestionCoordinator(work, chunks, index, tx, scheduler, metrics);
            assertEquals(IngestionCoordinator.Outcome.COMPLETED, coordinator.process(delivery));
            assertEquals(IngestionCoordinator.Outcome.SKIPPED, coordinator.process(delivery));
        }
        assertEquals(2, count("document_chunks"));
        assertEquals("SUCCESS", jdbc.sql("SELECT status FROM search_index_operations").query(String.class).single());
        assertTrue(chunks.isCurrent(tenant, document, generation(document), IDENTITY));
        verify(index, times(1)).index(any());
        assertEquals(0, count("document_artifact_readers"));
    }

    @Test
    void rolledBackPublicationLeavesNoIntentAndReplacementFencesLateCompletion() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(_ -> {
            publish(null); throw new IllegalStateException("revoked extraction claim");
        }));
        assertEquals(0, count("documents")); assertEquals(0, count("search_index_operations"));
        var document = publish(null);
        var first = generation(document);
        doAnswer(_ -> { publish(document); return null; }).when(index).index(any());
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var metrics = new SimpleMeterRegistry();
            var coordinator = new SearchIngestionCoordinator(work, chunks, index, tx, scheduler, metrics);
            assertEquals(IngestionCoordinator.Outcome.SKIPPED, coordinator.process(delivery()));
        }
        assertNotEquals(first, generation(document));
        assertFalse(chunks.isCurrent(tenant, document, first, IDENTITY));
        assertEquals(2, count("search_index_operations"));
        assertTrue(jdbc.sql("SELECT searchable_generation IS NULL FROM documents").query(Boolean.class).single());
    }

    @Test
    void providerFailureRetainsRetryAndAnExpiredClaimCannotComplete() {
        var document = publish(null);
        doThrow(new SearchUnavailableException()).when(index).index(any());
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var metrics = new SimpleMeterRegistry();
            var coordinator = new SearchIngestionCoordinator(work, chunks, index, tx, scheduler, metrics);
            assertEquals(IngestionCoordinator.Outcome.FAILED, coordinator.process(delivery()));
        }
        assertEquals("NOT_STARTED", jdbc.sql("SELECT status FROM search_index_operations").query(String.class).single());
        assertFalse(chunks.isCurrent(tenant, document, generation(document), IDENTITY));
        jdbc.sql("UPDATE search_index_operations SET next_dispatch_at=CURRENT_TIMESTAMP WHERE document_id=:document")
                .param("document", document.value()).update();
        var first = tx.execute(_ -> work.claim(delivery(), IDENTITY).orElseThrow());
        jdbc.sql("UPDATE search_index_operations SET lease_expires_at=CURRENT_TIMESTAMP - INTERVAL '1' SECOND, next_dispatch_at=CURRENT_TIMESTAMP WHERE document_id=:document")
                .param("document", document.value()).update();
        var second = tx.execute(_ -> work.claim(delivery(), IDENTITY).orElseThrow());
        assertNotEquals(first.token(), second.token());
        assertFalse(work.finish(first, "SUCCESS", null));
        assertTrue(work.finish(second, "SUCCESS", null));
    }

    @Test
    void publishesMultipleChunkBatchesAndRollsBackReplacementWhenALaterBatchFails() {
        var document = publish(null);
        var generation = generation(document);
        var reader = tx.execute(_ -> chunkRepository.openReader(tenant, document, generation).orElseThrow());
        var expected = IntStream.range(0, 259).mapToObj(ordinal -> {
            String text = "Dòng " + ordinal + ": 'nghỉ phép'; \"nội dung\" ? :value";
            return new DocumentChunk(ordinal, text, List.of("Quy định", "Mục " + ordinal), ordinal, 0,
                    "[{\"page_no\":1}]", StructuredDocumentChunker.sha256(text), 30);
        }).toList();
        try {
            assertEquals(Boolean.TRUE, tx.execute(_ -> chunkRepository.publish(reader, expected)));
            assertEquals(expected, chunkRepository.load(tenant, document, generation).orElseThrow().chunks());

            var invalidReplacement = new ArrayList<>(expected);
            invalidReplacement.set(128, expected.getFirst());
            assertThrows(DataIntegrityViolationException.class,
                    () -> tx.execute(_ -> chunkRepository.publish(reader, invalidReplacement)));

            // The failed second statement must roll back the first statement and the initial DELETE.
            assertEquals(expected, chunkRepository.load(tenant, document, generation).orElseThrow().chunks());
            assertEquals(259, jdbc.sql("SELECT chunk_count FROM documents WHERE id=:id")
                    .param("id", document.value()).query(Integer.class).single());
        } finally {
            chunkRepository.closeReader(reader.readerId());
        }
    }

    @Test
    void removalRetainsDeleteWorkAfterDocumentAndChunksAreGone() {
        var document = publish(null);
        tx.executeWithoutResult(_ -> documents.removeUnreferenced(tenant, List.of(document)));
        assertEquals(0, count("documents"));
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM search_index_operations WHERE action='DELETE'").query(Integer.class).single());
    }

    private DocumentId publish(DocumentId existing) {
        UUID artifact = UUID.randomUUID();
        artifacts.stage(tenant, artifact, "extracted/" + artifact, StructuredDocumentChunker.sha256(JSON), JSON.getBytes(StandardCharsets.UTF_8).length);
        artifacts.finishWrite(tenant, artifact);
        return tx.execute(_ -> documents.publish(tenant, existing,
                new DocumentContent("text/plain", "HR-2026", "Nghỉ phép", Map.of(), JSON, artifact), "a".repeat(64)));
    }
    private OperationDelivery delivery() {
        return tx.execute(_ -> {
            var claim = dispatch.claim(OperationWorkload.SEARCH, 1).getFirst();
            dispatch.recordPublished(claim, "1000-0", Duration.ofMinutes(2));
            return claim.delivery();
        });
    }
    private UUID generation(DocumentId document) {
        return jdbc.sql("SELECT content_generation FROM documents WHERE id=:id").param("id", document.value()).query(UUID.class).single();
    }
    private int count(String table) { return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single(); }
}
