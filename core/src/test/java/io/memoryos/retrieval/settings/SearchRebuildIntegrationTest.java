package io.memoryos.retrieval.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.connector.DocumentAccess;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.document.DocumentChanged;
import io.memoryos.document.DocumentChunk;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.DocumentId;
import io.memoryos.document.application.DocumentChunkService;
import io.memoryos.document.application.StructuredDocumentChunker;
import io.memoryos.document.persistence.JdbcDocumentChunkRepository;
import io.memoryos.document.persistence.JdbcDocumentRepository;
import io.memoryos.document.persistence.JdbcExtractionArtifactRepository;
import io.memoryos.iam.group.Authority;
import io.memoryos.iam.group.IamAccess;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.ingestion.IngestionCoordinator;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.ingestion.application.SearchIngestionCoordinator;
import io.memoryos.ingestion.application.SearchProjectionMaintenance;
import io.memoryos.ingestion.persistence.JdbcOperationDispatchRepository;
import io.memoryos.ingestion.persistence.JdbcSearchWorkRepository;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.retrieval.SearchHit;
import io.memoryos.retrieval.SearchTimings;
import io.memoryos.retrieval.opensearch.OpenSearchIndexService;
import io.memoryos.retrieval.opensearch.TestSearchGateways;
import io.memoryos.retrieval.opensearch.SearchProperties;
import io.memoryos.retrieval.settings.persistence.JdbcSearchSettingsRepository;
import io.memoryos.usage.AiUsageRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/**
 * MEM-135 acceptance: a FUTURE index is rebuilt from stored chunks beside PRESENT and switched, cancelled, restored and
 * cleaned up, against real PostgreSQL, real OpenSearch and an OpenAI-compatible embedding endpoint.
 */
@Testcontainers
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class SearchRebuildIntegrationTest {
    @Container
    @SuppressWarnings("resource") // JUnit's Testcontainers extension owns start/stop.
    static final GenericContainer<?> OPENSEARCH = new GenericContainer<>("opensearchproject/opensearch:3.8.0@sha256:bcc1797519726ceb6d651d4a3e60b7c30da91793914a8dfe75fd441d4f641509")
            .withEnv("discovery.type", "single-node").withEnv("DISABLE_SECURITY_PLUGIN", "true")
            .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true").withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200).waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(3)));

    private static final String MASTER_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String OLD_MODEL = "old-embedding";
    private static final String NEW_MODEL = "Qwen/Qwen3-Embedding-0.6B";
    private static final String QUERY_PREFIX = "Instruct: Given a question, retrieve passages that answer it\nQuery: ";

    private HikariDataSource database;
    private JdbcClient jdbc;
    private JdbcTransactionManager transactions;
    private TransactionTemplate tx;
    private FakeEmbeddingServer embeddings;
    private SearchProperties properties;
    private final UUID tenant = UUID.randomUUID();
    private final ActorId admin = new ActorId(UUID.randomUUID());
    private final TenantAccessResolver tenants = mock(TenantAccessResolver.class);
    private final IamAuthorization authorization = mock(IamAuthorization.class);
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-24T08:00:00Z"));
    private ScheduledExecutorService scheduler;
    private JdbcExtractionArtifactRepository artifacts;
    private JdbcDocumentRepository documents;
    private JdbcOperationDispatchRepository dispatch;
    private Process process;

    /** One api or worker process: its own generation cache, index service and search work. */
    private final class Process {
        final SearchGenerations generations;
        final OpenSearchIndexService index;
        final DocumentChunkService chunks;
        final JdbcSearchWorkRepository work;
        final SearchProjectionMaintenance maintenance;
        final SearchIngestionCoordinator coordinator;
        final SearchSettingsService settings;

        Process(int rebuildWindow) throws Exception {
            var mapper = new ObjectMapper();
            var repository = new JdbcSearchSettingsRepository(jdbc);
            var credentials = new EmbeddingProviderCredentials(MASTER_KEY, "deployment-key");
            @SuppressWarnings("unchecked")
            ObjectProvider<AiUsageRecorder> usage = mock(ObjectProvider.class);
            generations = new SearchGenerations(repository, tenants, credentials, properties, transactions,
                    ObservationRegistry.NOOP, usage, "");
            var storage = mock(ObjectStorage.class);
            when(storage.open(any())).thenAnswer(call -> content(objects.get(call.<ObjectKey>getArgument(0).value())));
            chunks = new DocumentChunkService(new JdbcDocumentChunkRepository(jdbc, mapper), storage,
                    new StructuredDocumentChunker(mapper));
            var sourceSearch = mock(SourceSearchService.class);
            when(sourceSearch.indexMetadata(any(), any(), any())).thenReturn(List.of());
            when(sourceSearch.indexAccess(any(), any())).thenReturn(new DocumentAccess(true, Set.of()));
            var gateway = TestSearchGateways.gateway(properties, mapper);
            index = new OpenSearchIndexService(gateway, generations, properties, mapper, chunks, sourceSearch,
                    new SearchTimings(new SimpleMeterRegistry(), ObservationRegistry.NOOP));
            work = new JdbcSearchWorkRepository(jdbc);
            maintenance = new SearchProjectionMaintenance(chunks, work, index, transactions, rebuildWindow);
            coordinator = new SearchIngestionCoordinator(work, chunks, index, tx, scheduler, new SimpleMeterRegistry());
            settings = new SearchSettingsService(repository, generations, index, chunks, authorization, tenants, credentials,
                    new EmbeddingProbe(properties, ObservationRegistry.NOOP), properties, transactions, clock());
        }

        /** Delivers and processes queued search work until none is due; returns how much ran. */
        int drain() {
            int processed = 0;
            for (int round = 0; round < 200; round++) {
                var claims = tx.execute(_ -> dispatch.claim(OperationWorkload.SEARCH, 20));
                if (claims == null || claims.isEmpty()) return processed;
                for (var claim : claims) {
                    tx.executeWithoutResult(_ -> dispatch.recordPublished(claim, "1000-0", Duration.ofMinutes(2)));
                    coordinator.process(claim.delivery());
                    processed++;
                }
            }
            throw new AssertionError("search work did not drain");
        }

        /** Feeds and drains the rebuild until the FUTURE holds every document. */
        void rebuildCompletely() {
            for (int round = 0; round < 50; round++) {
                int queued = maintenance.rebuild();
                int ran = drain();
                if (queued == 0 && ran == 0) return;
            }
            throw new AssertionError("rebuild did not finish");
        }

        List<SearchHit> search(String question) {
            return index.search(new TenantId(tenant), question, List.of(), null, Set.of());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(database);
        transactions = new JdbcTransactionManager(database);
        tx = new TransactionTemplate(transactions);
        embeddings = new FakeEmbeddingServer();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        properties = new SearchProperties(new URI("http", null, OPENSEARCH.getHost(), OPENSEARCH.getMappedPort(9200), null, null, null),
                "", "", "", embeddings.endpoint(), "deployment-key", OLD_MODEL, 8, 32, 2, Duration.ofSeconds(5), 0, 50, .5,
                .70, Duration.ofSeconds(30), "memoryos-t" + Long.toHexString(System.nanoTime()), 0, "", "");
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'ops','Ops','ACTIVE','TEST')")
                .param("id", tenant).update();
        when(tenants.operatingTenant()).thenReturn(java.util.Optional.of(new TenantId(tenant)));
        var access = new IamAccess(new TenantId(tenant), Authority.GLOBAL);
        when(authorization.require(any(), any(), anyBoolean())).thenReturn(access);
        when(authorization.lockAndRequireExclusive(any(), any())).thenReturn(access);
        artifacts = new JdbcExtractionArtifactRepository(jdbc);
        dispatch = new JdbcOperationDispatchRepository(jdbc);
        process = new Process(2);
        documents = new JdbcDocumentRepository(jdbc, new ObjectMapper(), event -> {
            if (event instanceof DocumentChanged changed) process.maintenance.changed(changed);
        });
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) scheduler.close();
        if (embeddings != null) embeddings.close();
        if (database != null) database.close();
    }

    @Test
    void searchKeepsAnsweringFromPresentDuringTheRebuildAndTheSwitchUsesTheNewModelAndQueryPrefix() {
        var corpus = seedCorpus();
        var provider = process.settings.createProvider(admin, new SearchSettingsService.ProviderInput("serving-embedding",
                embeddings.endpoint(), "tei-key", EmbeddingProvider.DataBoundary.INTERNAL, null));
        var future = process.settings.createFuture(admin, new SearchSettingsService.GenerationInput(provider.id(), NEW_MODEL, 4,
                QUERY_PREFIX, "", .5));
        assertEquals(SearchGeneration.Status.FUTURE, future.generation().status());
        assertTrue(process.index.indexExists(future.generation().identity()), "The FUTURE index is created with its generation");
        // Creating the FUTURE made one real embedding call with the model, dimensions and key it will index with.
        var check = embeddings.callsFor(NEW_MODEL).getFirst();
        assertEquals(4, check.dimensions());
        assertEquals("Bearer tei-key", check.authorization());

        var progress = process.settings.settings(admin).rebuild();
        assertNotNull(progress);
        assertEquals(new SearchSettingsService.RebuildProgress(0, 3, 0, 3, null, false), progress);
        var refused = assertThrows(SearchSettingsException.class, () -> process.settings.switchFuture(admin));
        assertEquals("SEARCH_SETTINGS_REBUILD_INCOMPLETE", refused.code());

        // One bounded window of the rebuild runs: the FUTURE holds a part of the corpus.
        assertEquals(2, process.maintenance.rebuild());
        assertEquals(2, process.drain());
        assertEquals(2, process.settings.settings(admin).rebuild().ready());
        embeddings.calls.clear();
        var during = process.search("chính sách nghỉ phép");
        assertEquals(ids(corpus), documentIds(during), "Search keeps serving every document from PRESENT");
        assertEquals(List.of(OLD_MODEL), embeddings.calls.stream().map(FakeEmbeddingServer.Call::model).distinct().toList());
        assertEquals("chính sách nghỉ phép", embeddings.calls.getFirst().inputs().getFirst());
        String present = process.generations.present().identity();
        for (var document : corpus) {
            assertTrue(process.chunks.isCurrent(new TenantId(tenant), document, generation(document), present),
                    "Rebuilding must not withdraw readiness in PRESENT");
        }
        assertEquals(3, jdbc.sql("SELECT COUNT(*) FROM documents WHERE search_index_identity=:present")
                .param("present", present).query(Integer.class).single(), "Source status keeps reading the served index");

        process.rebuildCompletely();
        var complete = process.settings.settings(admin).rebuild();
        assertEquals(new SearchSettingsService.RebuildProgress(3, 3, 0, 0, 0L, true), complete);

        var switched = process.settings.switchFuture(admin);
        assertEquals(future.generation().id(), switched.present().generation().id());
        assertNull(switched.future());
        var past = switched.past().getFirst();
        assertEquals(present, past.generation().identity());
        assertEquals(now.get().plus(Duration.ofDays(7)), past.generation().retainedUntil());
        assertEquals(3, jdbc.sql("SELECT COUNT(*) FROM documents WHERE search_index_identity=:future")
                .param("future", future.generation().identity()).query(Integer.class).single());

        embeddings.calls.clear();
        var after = process.search("chính sách nghỉ phép");
        assertEquals(ids(corpus), documentIds(after));
        var query = embeddings.calls.getFirst();
        assertEquals(NEW_MODEL, query.model());
        assertEquals(4, query.dimensions());
        assertEquals(QUERY_PREFIX + "chính sách nghỉ phép", query.inputs().getFirst(), "Questions carry the new query prefix");
    }

    @Test
    void anotherProcessPicksUpTheSwitchWithinItsRefreshInterval() throws Exception {
        seedCorpus();
        var api = new Process(2);
        api.generations.refreshEvery(Duration.ZERO);
        String before = api.generations.present().identity();
        startFuture();
        process.rebuildCompletely();
        process.settings.switchFuture(admin);
        String after = process.generations.present().identity();
        assertFalse(before.equals(after));
        assertEquals(after, api.generations.present().identity(), "The api reads the switched generation without a restart");
        assertEquals(List.of(after), api.generations.identities());
    }

    @Test
    void aWorkerRestartedMidRebuildResumesWhereTheLastOneStopped() throws Exception {
        var corpus = seedCorpus();
        var future = startFuture();
        assertEquals(2, process.maintenance.rebuild());
        // The worker claims one document and dies before finishing it; its lease and the redelivery delay run out.
        var delivery = tx.execute(_ -> {
            var claim = dispatch.claim(OperationWorkload.SEARCH, 1).getFirst();
            dispatch.recordPublished(claim, "1000-0", Duration.ofMinutes(2));
            return claim.delivery();
        });
        tx.execute(_ -> process.work.claim(delivery).orElseThrow());
        jdbc.sql("""
                UPDATE search_index_operations SET lease_expires_at=CURRENT_TIMESTAMP - INTERVAL '1' SECOND,
                    next_dispatch_at=CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE status='IN_PROGRESS'
                """).update();

        var restarted = new Process(2);
        restarted.rebuildCompletely();
        for (var document : corpus) {
            assertTrue(restarted.chunks.isCurrent(new TenantId(tenant), document, generation(document), future.identity()));
        }
        assertTrue(restarted.settings.settings(admin).rebuild().switchable());
    }

    @Test
    void aDocumentChangedDuringTheRebuildAppearsInTheNewIndex() {
        var corpus = seedCorpus();
        var future = startFuture();
        process.rebuildCompletely();
        var changed = corpus.getFirst();
        publish(changed, "Quy trình mua sắm thiết bị", "Phòng mua sắm phê duyệt thiết bị văn phòng trong ba ngày.");
        // The change was queued for both indexes; before it is indexed the rebuild is not complete.
        assertEquals(2, jdbc.sql("""
                SELECT COUNT(DISTINCT index_identity) FROM search_index_operations WHERE document_id=:document AND generation=:generation
                """).param("document", changed.value()).param("generation", generation(changed)).query(Integer.class).single());
        assertFalse(process.settings.settings(admin).rebuild().switchable());
        process.rebuildCompletely();
        process.settings.switchFuture(admin);

        var hits = process.search("phê duyệt thiết bị văn phòng");
        assertTrue(hits.stream().anyMatch(hit -> hit.documentId().equals(changed.value()) && hit.generation().equals(generation(changed))),
                "The new index serves the changed content");
        assertTrue(process.chunks.isCurrent(new TenantId(tenant), changed, generation(changed), future.identity()));
    }

    @Test
    void cancellingDeletesTheFutureIndexAndItsQueuedWork() {
        seedCorpus();
        var future = startFuture();
        assertEquals(2, process.maintenance.rebuild());
        process.drain();
        assertEquals(1, process.maintenance.rebuild());

        process.settings.cancelFuture(admin);

        assertFalse(process.index.indexExists(future.identity()));
        assertEquals(0, count("search_settings WHERE id='" + future.id() + "'"));
        assertEquals(0, count("document_search_projection WHERE index_identity='" + future.identity() + "'"));
        assertEquals(0, count("search_index_operations WHERE index_identity='" + future.identity()
                + "' AND status IN ('NOT_STARTED','IN_PROGRESS')"));
        assertEquals(List.of(process.generations.present().identity()), process.generations.identities());
        assertEquals(0, process.drain(), "Cancelled work is never processed");
        assertEquals(3, documentIds(process.search("chính sách nghỉ phép")).size(), "PRESENT keeps serving");
        assertThrows(SearchSettingsException.class, () -> process.settings.cancelFuture(admin));
    }

    @Test
    void aPastIndexCanBeRestoredWithinRetentionAndIsDeletedWithARecountAfterIt() {
        var corpus = seedCorpus();
        String original = process.generations.present().identity();
        var future = startFuture();
        process.rebuildCompletely();
        process.settings.switchFuture(admin);
        var past = pastGeneration(original);

        now.set(now.get().plus(Duration.ofDays(6)));
        var restored = process.settings.restorePast(admin, past.id());
        assertEquals(original, restored.present().generation().identity());
        assertEquals(future.identity(), restored.past().getFirst().generation().identity());
        embeddings.calls.clear();
        assertEquals(ids(corpus), documentIds(process.search("chính sách nghỉ phép")));
        assertEquals(OLD_MODEL, embeddings.calls.getFirst().model(), "Restored search embeds with the old model again");
        assertEquals(3, jdbc.sql("SELECT COUNT(*) FROM documents WHERE search_index_identity=:original")
                .param("original", original).query(Integer.class).single());

        // The generation replaced by the restore is kept for its own seven days; restoring it needs no FUTURE.
        var replaced = pastGeneration(future.identity());
        now.set(now.get().plus(Duration.ofDays(7)).plusSeconds(1));
        var expired = assertThrows(SearchSettingsException.class, () -> process.settings.restorePast(admin, replaced.id()));
        assertEquals("SEARCH_SETTINGS_RETENTION_ENDED", expired.code());

        assertEquals(1, process.settings.cleanupExpired());
        assertFalse(process.index.indexExists(future.identity()), "The expired index is gone and counts zero");
        assertEquals(0, count("search_settings WHERE id='" + replaced.id() + "'"));
        assertEquals(0, count("document_search_projection WHERE index_identity='" + future.identity() + "'"));
        assertTrue(process.settings.settings(admin).past().isEmpty());
        assertEquals(3, documentIds(process.search("chính sách nghỉ phép")).size());
    }

    @Test
    void repeatedCleanupFailuresBlockTheGenerationAndItIsShownUntilTheIndexIsDeleted() {
        seedCorpus();
        String original = process.generations.present().identity();
        startFuture();
        process.rebuildCompletely();
        process.settings.switchFuture(admin);
        var past = pastGeneration(original);
        now.set(now.get().plus(Duration.ofDays(8)));

        var failing = spy(process.index);
        doReturn(false).when(failing).deleteIndex(any());
        var repository = new JdbcSearchSettingsRepository(jdbc);
        var credentials = new EmbeddingProviderCredentials(MASTER_KEY, "deployment-key");
        var settings = new SearchSettingsService(repository, process.generations, failing, process.chunks, authorization, tenants,
                credentials, new EmbeddingProbe(properties, ObservationRegistry.NOOP), properties, transactions, clock());
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertEquals(0, settings.cleanupExpired());
            assertEquals(attempt == 3, settings.settings(admin).past().stream().anyMatch(SearchSettingsService.GenerationView::cleanupBlocked));
        }
        assertEquals(3, jdbc.sql("SELECT cleanup_attempts FROM search_settings WHERE id=:id").param("id", past.id()).query(Integer.class).single());

        assertEquals(1, process.settings.cleanupExpired(), "A blocked generation is still retried");
        assertFalse(process.index.indexExists(original));
        assertTrue(process.settings.settings(admin).past().isEmpty());
    }

    @Test
    void aNewChunkConventionRebuildsAutomaticallyAndSwitchesItself() {
        // PRESENT was built by a release with an older chunk convention.
        process.generations.present();
        jdbc.sql("UPDATE search_settings SET chunk_convention='structured-cl100k-768-v2' WHERE status='PRESENT'").update();
        process.generations.invalidate();
        var corpus = seedCorpus();
        var old = process.generations.present().generation();
        assertEquals("structured-cl100k-768-v2", old.chunkConvention());

        process.settings.rebuildForChunkConvention();
        var automatic = new JdbcSearchSettingsRepository(jdbc).future(tenant).orElseThrow();
        assertTrue(automatic.automatic());
        assertEquals(DocumentChunk.CONVENTION, automatic.chunkConvention());
        assertEquals(old.model(), automatic.model());
        assertEquals(old.providerId(), automatic.providerId());
        process.settings.rebuildForChunkConvention();
        assertEquals(1, count("search_settings WHERE status='FUTURE'"), "Another process starting finds the rebuild running");

        assertFalse(process.settings.switchAutomaticWhenComplete());
        process.rebuildCompletely();
        // The PRESENT repair pass during the rebuild keeps every document served.
        process.maintenance.reconcile();
        for (var document : corpus) {
            assertTrue(process.chunks.isCurrent(new TenantId(tenant), document, generation(document), old.identity()));
        }
        assertTrue(process.settings.switchAutomaticWhenComplete());
        var present = process.generations.present().generation();
        assertEquals(automatic.id(), present.id());
        assertEquals(DocumentChunk.CONVENTION, present.chunkConvention());
        assertEquals(ids(corpus), documentIds(process.search("chính sách nghỉ phép")));
        process.settings.rebuildForChunkConvention();
        assertEquals(0, count("search_settings WHERE status='FUTURE'"), "PRESENT now has the release's convention");
    }

    @Test
    void aSecondFutureIsRefusedWhileOneIsBeingRebuilt() {
        seedCorpus();
        var first = startFuture();
        var provider = new JdbcSearchSettingsRepository(jdbc).providers(tenant).getFirst();
        var second = assertThrows(SearchSettingsException.class, () -> process.settings.createFuture(admin,
                new SearchSettingsService.GenerationInput(provider.id(), "text-embedding-3-small", 8, "", "", .7)));
        assertEquals("SEARCH_SETTINGS_FUTURE_EXISTS", second.code());
        assertEquals(1, count("search_settings WHERE status='FUTURE'"));
        assertEquals(first.id(), process.generations.future().orElseThrow().generation().id());
    }

    @Test
    void aProviderThatRefusesTheModelStartsNoRebuild() {
        seedCorpus();
        var provider = new JdbcSearchSettingsRepository(jdbc).providers(tenant).getFirst();
        embeddings.failWith = 401;
        var rejected = assertThrows(SearchSettingsException.class, () -> process.settings.createFuture(admin,
                new SearchSettingsService.GenerationInput(provider.id(), NEW_MODEL, 4, QUERY_PREFIX, "", .5)));
        assertEquals("SEARCH_SETTINGS_EMBEDDING_REJECTED", rejected.code());
        assertFalse(rejected.getMessage().contains("private provider details"));
        assertEquals(0, count("search_settings WHERE status='FUTURE'"));
        embeddings.failWith = 0;
        var test = process.settings.testProvider(admin, new SearchSettingsService.TestInput(provider.id(), null, null, NEW_MODEL, null));
        assertTrue(test.ok());
        assertEquals(8, test.dimensions());
        assertEquals(NEW_MODEL, test.model());
    }

    // ---- fixtures ----

    /** Three indexed documents in PRESENT. */
    private List<DocumentId> seedCorpus() {
        process.generations.present();
        var corpus = List.of(
                publish(null, "Chính sách nghỉ phép 2026", "Nhân viên có 12 ngày nghỉ phép năm theo chính sách."),
                publish(null, "Nghỉ phép không lương", "Chính sách nghỉ phép không lương cần trưởng phòng duyệt."),
                publish(null, "Nghỉ phép thai sản", "Chính sách nghỉ phép thai sản theo luật lao động."));
        process.drain();
        String present = process.generations.present().identity();
        for (var document : corpus) {
            assertTrue(process.chunks.isCurrent(new TenantId(tenant), document, generation(document), present));
        }
        return corpus;
    }

    private SearchGeneration startFuture() {
        var provider = process.settings.createProvider(admin, new SearchSettingsService.ProviderInput("serving-embedding-" + UUID.randomUUID(),
                embeddings.endpoint(), "tei-key", EmbeddingProvider.DataBoundary.INTERNAL, null));
        return process.settings.createFuture(admin, new SearchSettingsService.GenerationInput(provider.id(), NEW_MODEL, 4,
                QUERY_PREFIX, "", .5)).generation();
    }

    private SearchGeneration pastGeneration(String identity) {
        return new JdbcSearchSettingsRepository(jdbc).generations(tenant).stream().map(JdbcSearchSettingsRepository.GenerationRow::generation)
                .filter(generation -> generation.identity().equals(identity)).findFirst().orElseThrow();
    }

    private DocumentId publish(DocumentId existing, String title, String text) {
        String json = """
                {"schema":"memoryos-extraction-v1","blocks":[
                {"index":0,"kind":"HEADING","headingLevel":1,"text":%s},
                {"index":1,"kind":"PARAGRAPH","text":%s,"provenance":[{"page_no":1}]}]}
                """.formatted(quote(title), quote(text));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        UUID artifact = UUID.randomUUID();
        String key = "extracted/" + artifact;
        objects.put(key, bytes);
        String sha = StructuredDocumentChunker.sha256(json);
        artifacts.stage(new TenantId(tenant), artifact, key, sha, bytes.length);
        artifacts.finishWrite(new TenantId(tenant), artifact);
        return tx.execute(_ -> documents.publish(new TenantId(tenant), existing,
                new DocumentContent("text/plain", title, text, Map.of(), json, artifact), sha));
    }

    private static String quote(String value) { return new ObjectMapper().writeValueAsString(value); }

    private static ObjectContent content(byte[] bytes) {
        return new ObjectContent() {
            @Override public ObjectMetadata metadata() { throw new UnsupportedOperationException(); }
            @Override public InputStream inputStream() { return new ByteArrayInputStream(bytes); }
            @Override public void close() { }
        };
    }

    private Clock clock() {
        return new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
    }

    private UUID generation(DocumentId document) {
        return jdbc.sql("SELECT content_generation FROM documents WHERE id=:id").param("id", document.value()).query(UUID.class).single();
    }

    private static Set<UUID> documentIds(List<SearchHit> hits) {
        var ids = new ArrayList<UUID>();
        hits.forEach(hit -> ids.add(hit.documentId()));
        return Set.copyOf(ids);
    }

    private static Set<UUID> ids(List<DocumentId> documents) {
        return Set.copyOf(documents.stream().map(DocumentId::value).toList());
    }

    private int count(String from) { return jdbc.sql("SELECT COUNT(*) FROM " + from).query(Integer.class).single(); }
}
