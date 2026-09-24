package io.memoryos.retrieval.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.document.DocumentChunk;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.retrieval.opensearch.SearchProperties;
import io.memoryos.retrieval.settings.persistence.JdbcSearchSettingsRepository;
import io.memoryos.usage.AiUsageRecorder;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** MEM-135: the first PRESENT generation is seeded from deployment configuration and keeps the index in use. */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class SearchGenerationsIntegrationTest {
    private HikariDataSource database;
    private JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();
    private final TenantAccessResolver tenants = mock(TenantAccessResolver.class);

    @BeforeEach
    void database() throws Exception {
        database = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(database);
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'ops','Ops','ACTIVE','TEST')")
                .param("id", tenant).update();
        when(tenants.operatingTenant()).thenReturn(Optional.of(new TenantId(tenant)));
    }

    @AfterEach
    void close() { database.close(); }

    @Test
    void seedsPresentFromDeploymentConfigurationWithTheIndexNameTheDeploymentAlreadyUses() {
        var staging = properties("https://api.openai.com/v1", "text-embedding-3-large", 3072);
        // The name OpenSearchIndexService computed before generations, written out independently of production code.
        String before = "memoryos-chunks-" + sha256("https://api.openai.com/v1:text-embedding-3-large:3072:" + DocumentChunk.CONVENTION)
                .substring(0, 16);

        var active = generations(staging).present();

        assertTrue(active.persisted());
        assertEquals(before, active.identity());
        assertEquals(before, SearchGenerations.legacyIdentity(staging));
        var stored = jdbc.sql("""
                SELECT s.model, s.dimensions, s.query_prefix, s.document_prefix, s.minimum_semantic_score, s.chunk_convention,
                    s.index_identity, s.status, s.automatic, s.activated_at IS NOT NULL AS activated,
                    p.name, p.endpoint, p.credential, p.data_boundary
                FROM search_settings s JOIN embedding_provider p ON p.tenant_id=s.tenant_id AND p.id=s.provider_id
                WHERE s.tenant_id=:tenant
                """).param("tenant", tenant).query().singleRow();
        assertEquals("text-embedding-3-large", stored.get("model"));
        assertEquals(3072, stored.get("dimensions"));
        assertEquals("", stored.get("query_prefix"));
        assertEquals("", stored.get("document_prefix"));
        assertEquals(.70, (Double) stored.get("minimum_semantic_score"), 1e-9);
        assertEquals(DocumentChunk.CONVENTION, stored.get("chunk_convention"));
        assertEquals(before, stored.get("index_identity"));
        assertEquals("PRESENT", stored.get("status"));
        assertEquals(false, stored.get("automatic"));
        assertEquals(true, stored.get("activated"));
        assertEquals("https://api.openai.com/v1", stored.get("endpoint"));
        // The seeded provider refers to the deployment key instead of copying it into the database.
        assertEquals(EmbeddingProviderCredentials.DEPLOYMENT, stored.get("credential"));
        assertEquals("EXTERNAL", stored.get("data_boundary"));
    }

    @Test
    void afterSeedingDeploymentConfigurationNoLongerDecidesTheModel() {
        var seeded = generations(properties("https://api.openai.com/v1", "text-embedding-3-large", 3072)).present();
        // A restart with other environment values: the stored generation still decides.
        var restarted = generations(properties("http://172.24.244.79:18090/v1", "Qwen/Qwen3-Embedding-0.6B", 1024)).present();
        assertEquals(seeded.generation().id(), restarted.generation().id());
        assertEquals("text-embedding-3-large", restarted.generation().model());
        assertEquals(3072, restarted.generation().dimensions());
        assertEquals(1L, count("search_settings"));
        assertEquals(1L, count("embedding_provider"));
    }

    @Test
    void theApiAndWorkerSeedingAtOnceLeaveOnePresentGeneration() throws Exception {
        var properties = properties("https://api.openai.com/v1", "text-embedding-3-large", 3072);
        var barrier = new CyclicBarrier(2);
        var api = CompletableFuture.supplyAsync(() -> { await(barrier); return generations(properties).present(); });
        var worker = CompletableFuture.supplyAsync(() -> { await(barrier); return generations(properties).present(); });
        assertEquals(api.get().generation().id(), worker.get().generation().id());
        assertEquals(1L, count("search_settings"));
        assertEquals(1L, count("embedding_provider"));
    }

    @Test
    void aTenantHasExactlyOnePresentAndAtMostOneFutureGeneration() {
        var present = generations(properties("https://api.openai.com/v1", "text-embedding-3-large", 3072)).present().generation();
        var repository = new JdbcSearchSettingsRepository(jdbc);
        assertThrows(DataIntegrityViolationException.class, () -> repository.insertGeneration(copy(present, SearchGeneration.Status.PRESENT)));
        repository.insertGeneration(copy(present, SearchGeneration.Status.FUTURE));
        assertThrows(DataIntegrityViolationException.class, () -> repository.insertGeneration(copy(present, SearchGeneration.Status.FUTURE)));
        // A provider in use cannot be deleted from under its generation.
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("DELETE FROM embedding_provider").update());
    }

    @Test
    void aFutureWhoseKeyCannotBeReadPausesOnlyTheRebuildAndRecoversWithoutARestart() {
        var properties = properties("https://api.openai.com/v1", "text-embedding-3-large", 3072);
        var present = generations(properties).present().generation();
        var repository = new JdbcSearchSettingsRepository(jdbc);
        // This process has no catalog encryption key, so the sealed key of the FUTURE's provider cannot be read.
        var provider = new EmbeddingProvider(UUID.randomUUID(), tenant, "serving-embedding", "http://10.0.0.5:8080/v1",
                "v1:00", EmbeddingProvider.DataBoundary.INTERNAL, 1);
        repository.insertProvider(provider);
        var id = UUID.randomUUID();
        var future = new SearchGeneration(id, tenant, provider.id(), "Qwen/Qwen3-Embedding-0.6B", 1024, "", "", .5,
                DocumentChunk.CONVENTION, SearchGeneration.identityFor("memoryos-chunks", id), SearchGeneration.Status.FUTURE,
                false, Instant.now(), null, null);
        repository.insertGeneration(future);

        var process = generations(properties);
        process.refreshEvery(Duration.ZERO);
        assertEquals(present.id(), process.present().generation().id(), "Search and PRESENT indexing keep their generation");
        assertEquals(java.util.List.of(present.identity()), process.identities());
        assertTrue(process.future().isEmpty());
        assertTrue(process.active(future.identity()).isEmpty(), "No work is written to the FUTURE meanwhile");
        assertTrue(process.active(present.identity()).isPresent());

        // The key becomes readable without any change to the generations' version; the next refresh picks it up.
        jdbc.sql("UPDATE embedding_provider SET credential=NULL WHERE id=:id").param("id", provider.id()).update();
        assertEquals(id, process.future().orElseThrow().generation().id());
        assertEquals(java.util.List.of(present.identity(), future.identity()), process.identities());
    }

    @Test
    void beforeTheOperatingTenantIsPublishedTheConfiguredGenerationIsServedUnsaved() {
        when(tenants.operatingTenant()).thenReturn(Optional.empty());
        var properties = properties("https://api.openai.com/v1", "text-embedding-3-large", 3072);
        var active = generations(properties).present();
        assertFalse(active.persisted());
        assertEquals(SearchGenerations.legacyIdentity(properties), active.identity());
        assertEquals(0L, count("search_settings"));
    }

    @Test
    void migrationBackfillsReadinessPerIndexFromTheDocumentColumns() throws Exception {
        try (var before = TestDatabase.freshPostgres("124")) {
            var old = JdbcClient.create(before);
            UUID owner = UUID.randomUUID(), ready = UUID.randomUUID(), pending = UUID.randomUUID(), generation = UUID.randomUUID();
            old.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'old','Old','ACTIVE','TEST')")
                    .param("id", owner).update();
            old.sql("""
                    INSERT INTO documents(id,tenant_id,status,title,content_generation,searchable_generation,search_index_identity)
                    VALUES (:ready,:tenant,'ELIGIBLE','Ready',:generation,:generation,'memoryos-chunks-0123456789abcdef'),
                           (:pending,:tenant,'ELIGIBLE','Pending',gen_random_uuid(),NULL,NULL)
                    """).param("ready", ready).param("pending", pending).param("tenant", owner).param("generation", generation).update();

            var flyway = Flyway.configure().dataSource(before).locations("classpath:db/migration").target("125").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);

            var rows = old.sql("SELECT document_id, index_identity, generation FROM document_search_projection").query().listOfRows();
            assertEquals(1, rows.size());
            assertEquals(ready, rows.getFirst().get("document_id"));
            assertEquals("memoryos-chunks-0123456789abcdef", rows.getFirst().get("index_identity"));
            assertEquals(generation, rows.getFirst().get("generation"));
        }
    }

    private SearchGenerations generations(SearchProperties properties) {
        @SuppressWarnings("unchecked")
        ObjectProvider<AiUsageRecorder> usage = mock(ObjectProvider.class);
        return new SearchGenerations(new JdbcSearchSettingsRepository(jdbc), tenants,
                new EmbeddingProviderCredentials("", properties.apiKey()), properties, new JdbcTransactionManager(database),
                ObservationRegistry.NOOP, usage, "");
    }

    private static SearchProperties properties(String endpoint, String model, int dimensions) {
        return new SearchProperties(URI.create("http://127.0.0.1:9200"), "", "", "", endpoint, "test-only-credential", model,
                dimensions, 32, 2, Duration.ofSeconds(1), 2, 500, .5, .70, Duration.ofSeconds(3), "memoryos-chunks", 0, "", "");
    }

    private static SearchGeneration copy(SearchGeneration generation, SearchGeneration.Status status) {
        var id = UUID.randomUUID();
        return new SearchGeneration(id, generation.tenantId(), generation.providerId(), generation.model(), generation.dimensions(),
                "", "", generation.minimumSemanticScore(), generation.chunkConvention(), SearchGeneration.identityFor("memoryos-chunks", id),
                status, false, Instant.now(), status == SearchGeneration.Status.PRESENT ? Instant.now() : null, null);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(); } catch (Exception interrupted) { throw new IllegalStateException(interrupted); }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
