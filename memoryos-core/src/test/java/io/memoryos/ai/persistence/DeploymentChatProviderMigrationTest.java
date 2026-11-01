package io.memoryos.ai.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V140 (MEM-211) removes the provider each Tenant was seeded with from the deployment's own key, and its models, as
 * deleting them in the catalog would: what named them is cleared, and what an administrator added is untouched.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class DeploymentChatProviderMigrationTest {
    @Test
    void theSeededProviderAndItsModelsGoWhileWhatTheAdministratorAddedStays() throws Exception {
        try (var database = TestDatabase.freshPostgres("139")) {
            var jdbc = JdbcClient.create(database);
            UUID tenant = UUID.randomUUID();
            jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'tasco','Tasco','ACTIVE','TEST')")
                    .param("id", tenant).update();
            UUID seeded = provider(jdbc, tenant, "OpenAI", "deployment", "deployment");
            UUID added = provider(jdbc, tenant, "9Router", "v1:encrypted", null);
            UUID seededDefault = model(jdbc, tenant, seeded, "gpt-5.6-luna");
            UUID seededOther = model(jdbc, tenant, seeded, "gpt-6-sol");
            UUID addedModel = model(jdbc, tenant, added, "cx/gpt-6-luna");
            jdbc.sql("INSERT INTO chat_model_default(tenant_id, model_configuration_id) VALUES (:tenant, :model)")
                    .param("tenant", tenant).param("model", seededDefault).update();
            flow(jdbc, tenant, "CHAT_NAMING", seededOther);
            flow(jdbc, tenant, "MEETING_MINUTES", addedModel);
            UUID onSeeded = agent(jdbc, tenant, "seeded", seededDefault);
            UUID onAdded = agent(jdbc, tenant, "added", addedModel);

            var flyway = Flyway.configure().dataSource(database).locations("classpath:db/migration").target("140").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);

            assertEquals(List.of(added), jdbc.sql("SELECT id FROM llm_provider").query(UUID.class).list());
            assertEquals(List.of(addedModel), jdbc.sql("SELECT id FROM model_configuration").query(UUID.class).list());
            assertTrue(unset(jdbc, "SELECT model_configuration_id IS NULL FROM chat_model_default"),
                    "the Chat default is unset, as an administrator must pick a model of their own");
            assertEquals(2L, jdbc.sql("SELECT revision FROM chat_model_default").query(Long.class).single());
            assertTrue(unset(jdbc, "SELECT model_configuration_id IS NULL FROM model_flow_default WHERE flow = 'CHAT_NAMING'"));
            assertEquals(addedModel, jdbc.sql("SELECT model_configuration_id FROM model_flow_default WHERE flow = 'MEETING_MINUTES'")
                    .query(UUID.class).single());
            assertTrue(unset(jdbc, "SELECT model_configuration_id IS NULL FROM persona WHERE id = '" + onSeeded + "'"));
            assertEquals(2L, jdbc.sql("SELECT model_revision FROM persona WHERE id = :id").param("id", onSeeded)
                    .query(Long.class).single());
            assertEquals(addedModel, jdbc.sql("SELECT model_configuration_id FROM persona WHERE id = :id").param("id", onAdded)
                    .query(UUID.class).single());
        }
    }

    private static boolean unset(JdbcClient jdbc, String query) {
        return jdbc.sql(query).query(Boolean.class).single();
    }

    private static UUID provider(JdbcClient jdbc, UUID tenant, String name, String credential, String builtinKey) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO llm_provider(id, tenant_id, builtin_key, name, adapter_type, base_url, enabled, is_public, credential,
                                         revision, data_boundary)
                VALUES (:id, :tenant, :builtin, :name, 'openai', 'https://example.test/v1', TRUE, TRUE, :credential, 1, 'EXTERNAL')
                """).param("id", id).param("tenant", tenant).param("builtin", builtinKey, Types.VARCHAR).param("name", name)
                .param("credential", credential).update();
        return id;
    }

    private static UUID model(JdbcClient jdbc, UUID tenant, UUID provider, String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO model_configuration(id, tenant_id, provider_id, model_name, display_name, visible, settings, revision)
                VALUES (:id, :tenant, :provider, :name, :name, TRUE, '{}'::jsonb, 1)
                """).param("id", id).param("tenant", tenant).param("provider", provider).param("name", name).update();
        return id;
    }

    private static void flow(JdbcClient jdbc, UUID tenant, String flow, UUID model) {
        jdbc.sql("INSERT INTO model_flow_default(tenant_id, flow, model_configuration_id) VALUES (:tenant, :flow, :model)")
                .param("tenant", tenant).param("flow", flow).param("model", model).update();
    }

    private static UUID agent(JdbcClient jdbc, UUID tenant, String name, UUID model) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO persona(id, tenant_id, name, instructions, model, model_configuration_id)
                VALUES (:id, :tenant, :name, '', 'gpt-6-luna', :model)
                """).param("id", id).param("tenant", tenant).param("name", name).param("model", model).update();
        return id;
    }
}
