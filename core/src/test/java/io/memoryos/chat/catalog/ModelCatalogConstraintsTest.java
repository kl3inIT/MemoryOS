package io.memoryos.chat.catalog;

import static org.junit.jupiter.api.Assertions.*;
import io.memoryos.TestDatabase;
import io.memoryos.chat.persistence.JdbcModelCatalogRepository;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ModelCatalogConstraintsTest {
    @Test
    void databaseRejectsCrossTenantModelsDefaultsGroupsAndPersonasAndPreservesDefaultProvider() throws Exception {
        var jdbc = JdbcClient.create(TestDatabase.freshPostgres());
        // Adversarial fixture only: production permits one Tenant per deployment. Keep all catalog FKs intact.
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        var catalog = new JdbcModelCatalogRepository(jdbc);
        UUID tenant = tenant(jdbc), otherTenant = tenant(jdbc), provider = UUID.randomUUID();
        catalog.initialize(tenant);
        catalog.initialize(otherTenant);
        catalog.insertProvider(new JdbcModelCatalogRepository.Provider(provider, tenant, "Provider", "openai", "http://internal/v1",
                true, true, "deployment", 1, Set.of(), Set.of()), null);
        var settings = new ModelSettings(32000, 4096, new ModelSettings.Capabilities(true, true, false, false), Map.of(), null);
        var model = new JdbcModelCatalogRepository.Model(UUID.randomUUID(), tenant, provider, "model", "Model", true, settings, 1);
        catalog.insertModel(model);
        assertThrows(DataIntegrityViolationException.class, () -> catalog.insertModel(new JdbcModelCatalogRepository.Model(
                UUID.randomUUID(), otherTenant, provider, "model", "Foreign", true, settings, 1)));
        assertThrows(DataIntegrityViolationException.class, () -> catalog.setDefault(otherTenant, model.id(), 1));
        UUID persona = UUID.randomUUID();
        jdbc.sql("INSERT INTO persona(id, tenant_id, builtin_key, name, instructions, model) VALUES (:id, :tenant, 'default', 'Persona', '', 'model')")
                .param("id", persona).param("tenant", otherTenant).update();
        assertThrows(DataIntegrityViolationException.class, () -> catalog.setPersonaModel(otherTenant, persona, model.id(), 1));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("INSERT INTO llm_provider_persona VALUES (:tenant, :provider, :persona)")
                .param("tenant", tenant).param("provider", provider).param("persona", persona).update());
        UUID group = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id, id, name) VALUES (:tenant, :id, 'Foreign')")
                .param("tenant", otherTenant).param("id", group).update();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("INSERT INTO llm_provider_group VALUES (:tenant, :provider, :group)")
                .param("tenant", tenant).param("provider", provider).param("group", group).update());
        catalog.setDefault(tenant, model.id(), 1);
        assertThrows(DataIntegrityViolationException.class, () -> catalog.deleteProvider(tenant, provider, 1));
    }
    private UUID tenant(JdbcClient jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'Models', 'ACTIVE', 'test')")
                .param("id", id).param("slug", id.toString()).update();
        return id;
    }
}
