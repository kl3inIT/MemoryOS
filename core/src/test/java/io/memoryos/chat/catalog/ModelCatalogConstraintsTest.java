package io.memoryos.chat.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.ModelCatalogRepository;
import io.memoryos.chat.persistence.JpaLlmProviderRepository;
import io.memoryos.chat.persistence.JpaModelConfigurationRepository;
import io.memoryos.chat.persistence.JpaChatModelDefaultRepository;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.Authority;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
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
            new ModelSettings.Capabilities(true, true, false, false), Map.of("temperature", 0.5), null, "openai-o200k-v1");

    @BeforeEach void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres("36"); jdbc = JdbcClient.create(dataSource); jpa = TestDatabase.jpa(dataSource);
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
        assertThrows(DataIntegrityViolationException.class, () -> tx(() -> catalog.setPersonaModel(otherTenant, UUID.randomUUID(), persona, model.id(), 1)));
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
        assertEquals(List.of(changed), read(() -> catalog.providers(tenant)));
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

    @Test void personaCursorRejectsForeignAndMissingAnchorsBeforeBuiltinProvisioning() {
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        UUID otherTenant = tenant(), foreign = UUID.randomUUID();
        jdbc.sql("INSERT INTO persona(id,tenant_id,builtin_key,name,instructions,model) VALUES (:id,:tenant,'default','Foreign','private','legacy')")
                .param("id", foreign).param("tenant", otherTenant).update();
        var authorization = mock(IamAuthorization.class);
        var actor = new ActorId(UUID.randomUUID());
        when(authorization.lockAndRequire(actor, IamCapability.MODELS_MANAGE, false))
                .thenReturn(new IamAccess(new TenantId(tenant), Authority.GLOBAL));
        var service = new ModelCatalogService(catalog, new JdbcChatRepository(jdbc), mock(TenantAccessResolver.class),
                authorization, new ChatProviderAdapters(List.of()), new ProviderCredentials("", ""), new PersonaProperties(),
                new ModelCatalogService.Deployment("http://internal/v1", "hosted", settings));
        var foreignFailure = assertThrows(ChatException.class, () -> read(() -> service.personas(actor, foreign.toString(), 25)));
        var missingFailure = assertThrows(ChatException.class, () -> read(() -> service.personas(actor, UUID.randomUUID().toString(), 25)));
        assertEquals(missingFailure.code(), foreignFailure.code());
        assertEquals(0, jdbc.sql("SELECT count(*) FROM persona WHERE tenant_id = :tenant")
                .param("tenant", tenant).query(Integer.class).single());
        var page = read(() -> service.personas(actor, null, 25));
        UUID builtin = jdbc.sql("SELECT id FROM persona WHERE tenant_id=:tenant AND builtin_key='default'")
                .param("tenant", tenant).query(UUID.class).single();
        assertEquals(List.of(new ModelCatalogRepository.PersonaSummary(builtin, new PersonaProperties().getName())), page.items());
        assertNull(page.nextCursor());
    }

    @Test void tokenizerMigrationBackfillsOnlyLegacyJsonAndPreservesCatalogSelectionsAndHistory() {
        var chats = new JdbcChatRepository(jdbc);
        UUID legacy = UUID.randomUUID(), installed = UUID.randomUUID();
        var actor = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES (:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant).param("actor", actor.value()).update();
        UUID persona = chats.provisionPersona(new TenantId(tenant), "Builtin", "Preserved instructions", "hosted");
        UUID group = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name) VALUES (:tenant,:id,'Models')")
                .param("tenant", tenant).param("id", group).update();
        var localSettings = new ModelSettings(1024, 128, new ModelSettings.Capabilities(true, false, false, false),
                Map.of(), null, "smollm2-135m-12fd25f-v1");
        tx(() -> {
            var original = catalog.provider(tenant, provider).orElseThrow();
            catalog.updateProvider(new ModelCatalogRepository.Provider(provider, tenant, original.name(), original.adapterType(),
                    original.baseUrl(), original.enabled(), original.isPublic(), original.credential(), original.revision(), Set.of(group), Set.of(persona)));
            catalog.insertModel(new ModelCatalogRepository.Model(installed, tenant, provider, "local", "Local", false, localSettings, 1));
            String legacySettings = """
                    {"contextWindow":8192,"maxOutputTokens":512,"capabilities":{"streaming":true,"toolCalling":false,"vision":false,"reasoning":false},
                     "options":{"temperature":0.2},"pricing":null}
                    """;
            jdbc.sql("""
                    INSERT INTO model_configuration(id,tenant_id,provider_id,model_name,display_name,settings,revision)
                    VALUES (:id,:tenant,:provider,'hosted','Hosted',CAST(:settings AS jsonb),9)
                    """).param("id", legacy).param("tenant", tenant).param("provider", provider).param("settings", legacySettings).update();
            catalog.setDefault(tenant, legacy, 1);
            catalog.setPersonaModel(tenant, actor.value(), persona, installed, 1);
            var session = chats.create(new TenantId(tenant), actor, persona, "Preserved history");
            UUID user = UUID.randomUUID(), assistant = UUID.randomUUID();
            chats.insertPair(session.id(), session.rootMessageId(), UUID.randomUUID(), user, assistant, "Tiếng Việt", Duration.ofMinutes(1));
            jdbc.sql("""
                    UPDATE chat_message SET status='COMPLETED',content='Preserved answer',finished_at=CURRENT_TIMESTAMP,
                        requested_model_configuration_id=:model,selected_model_configuration_id=:model
                    WHERE id=:id
                    """).param("model", installed).param("id", assistant).update();
        });
        jdbc.sql("UPDATE model_configuration SET revision=4 WHERE id=:id").param("id", installed).update();
        var preserved = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("llm_provider", "llm_provider_group", "llm_provider_persona", "chat_model_default",
                "persona", "chat_session", "chat_message")) {
            preserved.put(table, jdbc.sql("SELECT * FROM " + table).query().listOfRows());
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("37").load().migrate();
        var restored = read(() -> catalog.model(tenant, legacy).orElseThrow());
        assertEquals(new ModelSettings(8192, 512, new ModelSettings.Capabilities(true, false, false, false),
                Map.of("temperature", 0.2), null, "openai-o200k-v1"), restored.settings());
        assertEquals(9, restored.revision());
        assertEquals(provider, restored.providerId());
        var local = read(() -> catalog.model(tenant, installed).orElseThrow());
        assertEquals(localSettings, local.settings());
        assertEquals(4, local.revision());
        assertFalse(local.visible());
        for (var entry : preserved.entrySet()) {
            assertEquals(Set.copyOf(entry.getValue()), Set.copyOf(jdbc.sql("SELECT * FROM " + entry.getKey()).query().listOfRows()), entry.getKey());
        }
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
