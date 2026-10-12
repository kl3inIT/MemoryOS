package io.memoryos.retrieval.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.memoryos.TestDatabase;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V144 (MEM-216) removes the embedding provider the first start seeded from the deployment's key when no search
 * generation uses it, as on staging and production; one still in use keeps its row and asks for a key instead.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class DeploymentEmbeddingProviderMigrationTest {
    @Test
    void anUnusedDeploymentProviderGoesAUsedOneLosesItsKeyReferenceAndEnteredKeysStay() throws Exception {
        try (var database = TestDatabase.freshPostgres("143")) {
            var jdbc = JdbcClient.create(database);
            UUID tenant = tenant(jdbc, "tasco");
            provider(jdbc, tenant, "Deployment", "deployment");
            UUID serving = provider(jdbc, tenant, "serving-embedding", "v1:sealed");
            generation(jdbc, tenant, serving, "memoryos-chunks-serving", "PRESENT");
            // A development database seeded against OpenAI still rebuilding on it.
            UUID used = provider(jdbc, tenant, "OpenAI", "deployment");
            generation(jdbc, tenant, used, "memoryos-chunks-openai", "FUTURE");

            var flyway = Flyway.configure().dataSource(database).locations("classpath:db/migration").target("144").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);

            // Compared as a set: PostgreSQL and Java order UUIDs differently.
            assertEquals(Set.of(serving, used), jdbc.sql("SELECT id FROM embedding_provider").query(UUID.class).set());
            assertEquals("v1:sealed", credential(jdbc, serving));
            assertEquals(1L, revision(jdbc, serving));
            assertNull(credential(jdbc, used), "a provider still in use keeps its row and asks for a key");
            assertEquals(2L, revision(jdbc, used), "a process that cached its client reloads it");
            assertEquals(0L, jdbc.sql("SELECT count(*) FROM embedding_provider WHERE credential = 'deployment'")
                    .query(Long.class).single());
        }
    }

    private static UUID tenant(JdbcClient jdbc, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,:slug,:slug,'ACTIVE','TEST')")
                .param("id", id).param("slug", slug).update();
        return id;
    }

    private static UUID provider(JdbcClient jdbc, UUID tenant, String name, String credential) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO embedding_provider(id, tenant_id, name, endpoint, credential)
                VALUES (:id, :tenant, :name, 'https://api.openai.com/v1', :credential)
                """).param("id", id).param("tenant", tenant).param("name", name).param("credential", credential).update();
        return id;
    }

    private static void generation(JdbcClient jdbc, UUID tenant, UUID provider, String identity, String status) {
        jdbc.sql("""
                INSERT INTO search_settings(id, tenant_id, provider_id, model, dimensions, minimum_semantic_score, chunk_convention,
                                            index_identity, status, activated_at)
                VALUES (:id, :tenant, :provider, 'text-embedding-3-large', 3072, 0.7, 'v1', :identity, :status,
                        CASE WHEN :status = 'PRESENT' THEN now() END)
                """).param("id", UUID.randomUUID()).param("tenant", tenant).param("provider", provider)
                .param("identity", identity).param("status", status).update();
    }

    private static @Nullable String credential(JdbcClient jdbc, UUID provider) {
        return jdbc.sql("SELECT credential FROM embedding_provider WHERE id = :id").param("id", provider)
                .query((row, number) -> row.getString(1)).list().getFirst();
    }

    private static long revision(JdbcClient jdbc, UUID provider) {
        return jdbc.sql("SELECT revision FROM embedding_provider WHERE id = :id").param("id", provider).query(Long.class).single();
    }
}
