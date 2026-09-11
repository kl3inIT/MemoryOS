package io.memoryos.chat.catalog;

import static org.junit.jupiter.api.Assertions.*;
import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.persistence.ModelCatalogRepository;
import io.memoryos.chat.persistence.JpaLlmProviderRepository;
import io.memoryos.chat.persistence.JpaModelConfigurationRepository;
import io.memoryos.chat.persistence.JpaChatModelDefaultRepository;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ModelCatalogConstraintsTest {
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TestDatabase.JpaHarness jpa;
    private TransactionTemplate tx;
    private ModelCatalogRepository catalog;
    private UUID tenant;
    private UUID provider;
    private final ModelSettings settings = new ModelSettings(32000, 4096,
            new ModelSettings.Capabilities(true, true, false, false), Map.of("temperature", 0.5), null);

    @BeforeEach void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres(); jdbc = JdbcClient.create(dataSource); jpa = TestDatabase.jpa(dataSource);
        tx = new TransactionTemplate(jpa.transactionManager());
        catalog = new ModelCatalogRepository(jdbc, jpa.repository(JpaLlmProviderRepository.class),
                jpa.repository(JpaModelConfigurationRepository.class), jpa.repository(JpaChatModelDefaultRepository.class));
        tenant = tenant(); provider = UUID.randomUUID();
        tx(() -> { catalog.initialize(tenant); catalog.insertProvider(new ModelCatalogRepository.Provider(provider, tenant,
                "Provider", "openai", "http://internal/v1", true, true, "deployment", 1, Set.of(), Set.of()), null); });
    }
    @AfterEach void close() { if (jpa != null) jpa.close(); if (dataSource != null) dataSource.close(); }

    @Test void databaseRejectsCrossTenantModelsDefaultsGroupsAndPersonasAndPreservesDefaultProvider() {
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        UUID otherTenant = tenant(); tx(() -> catalog.initialize(otherTenant));
        var model = new ModelCatalogRepository.Model(UUID.randomUUID(), tenant, provider, "model", "Model", true, settings, 1);
        tx(() -> catalog.insertModel(model));
        assertThrows(DataIntegrityViolationException.class, () -> tx(() -> catalog.insertModel(new ModelCatalogRepository.Model(
                UUID.randomUUID(), otherTenant, provider, "model", "Foreign", true, settings, 1))));
        assertThrows(DataIntegrityViolationException.class, () -> tx(() -> catalog.setDefault(otherTenant, model.id(), 1)));
        UUID persona = UUID.randomUUID();
        jdbc.sql("INSERT INTO persona(id, tenant_id, builtin_key, name, instructions, model) VALUES (:id, :tenant, 'default', 'Persona', '', 'model')")
                .param("id", persona).param("tenant", otherTenant).update();
        assertThrows(DataIntegrityViolationException.class, () -> tx(() -> catalog.setPersonaModel(otherTenant, persona, model.id(), 1)));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("INSERT INTO llm_provider_persona VALUES (:tenant, :provider, :persona)")
                .param("tenant", tenant).param("provider", provider).param("persona", persona).update());
        UUID group = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id, id, name) VALUES (:tenant, :id, 'Foreign')")
                .param("tenant", otherTenant).param("id", group).update();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("INSERT INTO llm_provider_group VALUES (:tenant, :provider, :group)")
                .param("tenant", tenant).param("provider", provider).param("group", group).update());
        tx(() -> catalog.setDefault(tenant, model.id(), 1));
        assertThrows(DataIntegrityViolationException.class, () -> tx(() -> catalog.deleteProvider(tenant, provider, 1)));
        assertTrue(read(() -> catalog.provider(tenant, provider).isPresent()));
    }

    @Test void springDataPreservesJsonRevisionsAssociationOnlyChangesAndMixedRollback() {
        var model = new ModelCatalogRepository.Model(UUID.randomUUID(), tenant, provider, "model", "Model", true, settings, 1);
        tx(() -> catalog.insertModel(model));
        assertEquals(settings, read(() -> catalog.model(tenant, model.id()).orElseThrow().settings()));
        assertEquals(1L, (long) read(() -> catalog.model(tenant, model.id()).orElseThrow().revision()));
        UUID group = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name) VALUES (:tenant,:id,'Allowed')").param("tenant", tenant).param("id", group).update();
        UUID persona = UUID.randomUUID();
        jdbc.sql("INSERT INTO persona(id,tenant_id,builtin_key,name,instructions,model) VALUES (:id,:tenant,'default','Default','','model')")
                .param("id", persona).param("tenant", tenant).update();
        var original = read(() -> catalog.provider(tenant, provider).orElseThrow());
        tx(() -> catalog.updateProvider(new ModelCatalogRepository.Provider(provider, tenant, original.name(), original.adapterType(),
                original.baseUrl(), original.enabled(), original.isPublic(), original.credential(), original.revision(), Set.of(group), Set.of(persona))));
        var changed = read(() -> catalog.provider(tenant, provider).orElseThrow());
        assertEquals(Set.of(group), changed.groupIds());
        assertEquals(Set.of(persona), changed.personaIds());
        assertTrue(changed.revision() > original.revision());
        assertThrows(ChatException.class, () -> tx(() -> catalog.updateProvider(original)));
        UUID rolledBack = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> tx(() -> {
            catalog.insertModel(new ModelCatalogRepository.Model(rolledBack, tenant, provider, "rollback", "Rollback", true, settings, 1));
            jdbc.sql("UPDATE llm_provider SET name='Rollback' WHERE id=:id").param("id", provider).update();
            throw new IllegalStateException("rollback");
        }));
        assertFalse(read(() -> catalog.model(tenant, rolledBack).isPresent()));
        assertEquals(original.name(), read(() -> catalog.provider(tenant, provider).orElseThrow().name()));
        tx(() -> catalog.deleteModel(tenant, model.id(), 1));
        assertFalse(read(() -> catalog.model(tenant, model.id()).isPresent()));
        tx(() -> catalog.deleteProvider(tenant, provider, changed.revision()));
        assertTrue(read(() -> catalog.providers(tenant).isEmpty()));
    }
    private void tx(Runnable operation) { tx.executeWithoutResult(_ -> operation.run()); }
    private <T> T read(Supplier<T> query) { return tx.execute(_ -> query.get()); }
    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'Models', 'ACTIVE', 'test')")
                .param("id", id).param("slug", id.toString()).update();
        return id;
    }
}
