package io.memoryos.ai;

import io.memoryos.chat.PersonaSummary;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatModelAccess;
import io.memoryos.chat.persistence.JdbcAgentModelRepository;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.ai.persistence.JpaChatModelDefaultRepository;
import io.memoryos.ai.persistence.JpaLlmProviderRepository;
import io.memoryos.ai.persistence.JpaModelConfigurationRepository;
import io.memoryos.ai.persistence.ModelCatalogRepository;
import io.memoryos.iam.Authority;
import io.memoryos.iam.IamAccess;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
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

    // Only the tokenizer backfill needs the pre-V54 schema; the other cases exercise the current agent schema.
    @BeforeEach void setup(TestInfo test) throws Exception {
        boolean legacy = test.getTestMethod().map(method -> method.getName().startsWith("tokenizerMigration")).orElse(false);
        dataSource = TestDatabase.freshPostgres(legacy ? "53" : "latest"); jdbc = JdbcClient.create(dataSource); jpa = TestDatabase.jpa(dataSource, false);
        tx = new TransactionTemplate(jpa.transactionManager());
        catalog = new ModelCatalogRepository(jdbc, jpa.repository(JpaLlmProviderRepository.class),
                jpa.repository(JpaModelConfigurationRepository.class), jpa.repository(JpaChatModelDefaultRepository.class));
        tenant = tenant(); provider = UUID.randomUUID();
        // The pre-V54 schema predates llm_provider.data_boundary, which the provider entity maps.
        if (legacy) tx(() -> { catalog.initialize(tenant); jdbc.sql("""
                INSERT INTO llm_provider(id,tenant_id,name,adapter_type,base_url,enabled,is_public,credential,revision)
                VALUES (:id,:tenant,'Provider','openai','http://internal/v1',true,true,'deployment',1)""")
                .param("id", provider).param("tenant", tenant).update(); });
        else tx(() -> { catalog.initialize(tenant); catalog.insertProvider(new LlmProvider(provider, tenant,
                "Provider", "openai", "http://internal/v1", true, true, "deployment", 1, Set.of(), Set.of(), DataBoundary.EXTERNAL), null); });
    }
    @AfterEach void close() { if (jpa != null) jpa.close(); if (dataSource != null) dataSource.close(); }

    @Test void databaseRejectsCrossTenantModelsDefaultsGroupsAndPersonasAndPreservesDefaultProvider() {
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        UUID otherTenant = tenant(); tx(() -> catalog.initialize(otherTenant));
        var model = new ModelConfiguration(UUID.randomUUID(), tenant, provider, "model", "Model", true, settings, 1);
        tx(() -> catalog.insertModel(model));
        assertThrows(DataIntegrityViolationException.class, () -> tx(() -> catalog.insertModel(new ModelConfiguration(
                UUID.randomUUID(), otherTenant, provider, "model", "Foreign", true, settings, 1))));
        assertThrows(DataIntegrityViolationException.class, () -> tx(() -> catalog.setDefault(otherTenant, model.id(), 1)));
        UUID persona = UUID.randomUUID();
        jdbc.sql("INSERT INTO persona(id, tenant_id, builtin_key, name, instructions, model) VALUES (:id, :tenant, 'default', 'Persona', '', 'model')")
                .param("id", persona).param("tenant", otherTenant).update();
        assertThrows(DataIntegrityViolationException.class, () -> tx(() -> new JdbcAgentModelRepository(jdbc).setPersonaModel(otherTenant, UUID.randomUUID(), true, persona, model.id(), 1)));
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
        var model = new ModelConfiguration(UUID.randomUUID(), tenant, provider, "model", "Model", true, settings, 1);
        tx(() -> catalog.insertModel(model));
        assertEquals(settings, read(() -> catalog.model(tenant, model.id()).orElseThrow().settings()));
        assertEquals(1L, (long) read(() -> catalog.model(tenant, model.id()).orElseThrow().revision()));
        UUID group = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name) VALUES (:tenant,:id,'Allowed')").param("tenant", tenant).param("id", group).update();
        UUID persona = UUID.randomUUID();
        jdbc.sql("INSERT INTO persona(id,tenant_id,builtin_key,name,instructions,model) VALUES (:id,:tenant,'default','Default','','model')")
                .param("id", persona).param("tenant", tenant).update();
        var original = read(() -> catalog.provider(tenant, provider).orElseThrow());
        tx(() -> catalog.updateProvider(new LlmProvider(provider, tenant, original.name(), original.adapterType(),
                original.baseUrl(), original.enabled(), original.isPublic(), original.credential(), original.revision(), Set.of(group), Set.of(persona), DataBoundary.INTERNAL)));
        var changed = read(() -> catalog.provider(tenant, provider).orElseThrow());
        assertEquals(Set.of(group), changed.groupIds());
        assertEquals(Set.of(persona), changed.personaIds());
        assertEquals(DataBoundary.INTERNAL, changed.dataBoundary());
        assertTrue(changed.revision() > original.revision());
        assertEquals(List.of(changed), read(() -> catalog.providers(tenant)));
        assertThrows(AiException.class, () -> tx(() -> catalog.updateProvider(original)));
        UUID rolledBack = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> tx(() -> {
            catalog.insertModel(new ModelConfiguration(rolledBack, tenant, provider, "rollback", "Rollback", true, settings, 1));
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

    @Test void flowDefaultsKeepRevisionsStayInTenantAndClearWhenTheirModelIsDeleted() {
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        UUID otherTenant = tenant();
        tx(() -> { catalog.initializeFlows(tenant); catalog.initializeFlows(tenant); catalog.initializeFlows(otherTenant); });
        var unset = read(() -> catalog.flowDefault(tenant, ModelFlow.CHAT_NAMING));
        assertNull(unset.modelConfigurationId());
        var defaults = read(() -> catalog.flowDefaults(tenant));
        assertEquals(ModelFlow.values().length, defaults.size(), "initializing seeds one row per task flow, once");
        assertEquals(unset, defaults.getFirst());
        var model = new ModelConfiguration(UUID.randomUUID(), tenant, provider, "mini", "Mini", true, settings, 1);
        tx(() -> catalog.insertModel(model));
        assertThrows(DataIntegrityViolationException.class,
                () -> tx(() -> catalog.setFlowDefault(otherTenant, ModelFlow.CHAT_NAMING, model.id(), 1)));
        tx(() -> catalog.setFlowDefault(tenant, ModelFlow.CHAT_NAMING, model.id(), unset.revision()));
        assertThrows(AiException.class, () -> tx(() -> catalog.setFlowDefault(tenant, ModelFlow.CHAT_NAMING, null, unset.revision())));
        var set = read(() -> catalog.flowDefault(tenant, ModelFlow.CHAT_NAMING));
        assertEquals(model.id(), set.modelConfigurationId());
        assertEquals(unset.revision() + 1, set.revision());
        tx(() -> catalog.deleteModel(tenant, model.id(), 1));
        assertNull(read(() -> catalog.flowDefault(tenant, ModelFlow.CHAT_NAMING)).modelConfigurationId());
        assertEquals(DataBoundary.EXTERNAL, read(() -> catalog.provider(tenant, provider).orElseThrow().dataBoundary()));
    }

    @Test void personaCursorRejectsForeignAndMissingAnchors() {
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        UUID otherTenant = tenant(), foreign = UUID.randomUUID();
        jdbc.sql("INSERT INTO persona(id,tenant_id,builtin_key,name,instructions,model) VALUES (:id,:tenant,'default','Foreign','private','legacy')")
                .param("id", foreign).param("tenant", otherTenant).update();
        var chats = new JdbcChatRepository(jdbc);
        UUID builtin = chats.provisionPersona(new TenantId(tenant), new PersonaProperties().getName(), "instructions", "hosted");
        var authorization = mock(IamAuthorization.class);
        var actor = new ActorId(UUID.randomUUID());
        when(authorization.require(actor, IamCapability.MODELS_MANAGE, false))
                .thenReturn(new IamAccess(new TenantId(tenant), Authority.GLOBAL));
        var service = new ChatModelAccess(mock(ModelCatalogService.class), new JdbcAgentModelRepository(jdbc), chats,
                mock(TenantAccessResolver.class), authorization);
        var foreignFailure = assertThrows(ChatException.class, () -> read(() -> service.personas(actor, foreign.toString(), 25)));
        var missingFailure = assertThrows(ChatException.class, () -> read(() -> service.personas(actor, UUID.randomUUID().toString(), 25)));
        assertEquals(missingFailure.code(), foreignFailure.code());
        var page = read(() -> service.personas(actor, null, 25));
        assertEquals(List.of(new PersonaSummary(builtin, new PersonaProperties().getName())), page.items());
        assertNull(page.nextCursor());
    }

    @Test void tokenizerMigrationBackfillsOnlyLegacyJsonAndPreservesCatalogSelectionsAndHistory() {
        UUID legacy = UUID.randomUUID(), installed = UUID.randomUUID();
        var actor = new ActorId(UUID.randomUUID());
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", actor.value()).update();
        jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES (:tenant,:actor,'MEMBER','ACTIVE')")
                .param("tenant", tenant).param("actor", actor.value()).update();
        // Raw SQL: the repository writes V74 agent tables that do not exist at the V53 baseline.
        UUID persona = UUID.randomUUID();
        jdbc.sql("INSERT INTO persona(id,tenant_id,builtin_key,name,instructions,model) VALUES (:id,:tenant,'default','Builtin','Preserved instructions','hosted')")
                .param("id", persona).param("tenant", tenant).update();
        UUID group = UUID.randomUUID();
        jdbc.sql("INSERT INTO iam_groups(tenant_id,id,name) VALUES (:tenant,:id,'Models')")
                .param("tenant", tenant).param("id", group).update();
        var localSettings = new ModelSettings(1024, 128, new ModelSettings.Capabilities(true, false, false, false),
                Map.of(), null, "custom-tokenizer-v1");
        tx(() -> {
            jdbc.sql("INSERT INTO llm_provider_group VALUES (:tenant, :provider, :group)")
                    .param("tenant", tenant).param("provider", provider).param("group", group).update();
            jdbc.sql("INSERT INTO llm_provider_persona VALUES (:tenant, :provider, :persona)")
                    .param("tenant", tenant).param("provider", provider).param("persona", persona).update();
            jdbc.sql("UPDATE llm_provider SET revision=revision+1 WHERE id=:id").param("id", provider).update();
            catalog.insertModel(new ModelConfiguration(installed, tenant, provider, "local", "Local", false, localSettings, 1));
            String legacySettings = """
                    {"contextWindow":8192,"maxOutputTokens":512,"capabilities":{"streaming":true,"toolCalling":false,"vision":false,"reasoning":false},
                     "options":{"temperature":0.2},"pricing":null}
                    """;
            jdbc.sql("""
                    INSERT INTO model_configuration(id,tenant_id,provider_id,model_name,display_name,settings,revision)
                    VALUES (:id,:tenant,:provider,'hosted','Hosted',CAST(:settings AS jsonb),9)
                    """).param("id", legacy).param("tenant", tenant).param("provider", provider).param("settings", legacySettings).update();
            catalog.setDefault(tenant, legacy, 1);
            jdbc.sql("UPDATE persona SET model_configuration_id=:model, model_revision=model_revision+1, revision=revision+1 WHERE id=:id")
                    .param("model", installed).param("id", persona).update();
            // Raw SQL again: the repository reads columns the V53 baseline does not have yet.
            UUID session = UUID.randomUUID(), root = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO chat_session(id,tenant_id,owner_actor_id,persona_id,root_message_id,title)
                    VALUES (:id,:tenant,:actor,:persona,:root,'Preserved history')
                    """).param("id", session).param("tenant", tenant).param("actor", actor.value())
                    .param("persona", persona).param("root", root).update();
            jdbc.sql("""
                    INSERT INTO chat_message(id,session_id,role,status,finished_at)
                    VALUES (:root,:session,'ROOT','COMPLETED',CURRENT_TIMESTAMP)
                    """).param("root", root).param("session", session).update();
            UUID user = UUID.randomUUID(), assistant = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO chat_message(id,session_id,parent_message_id,role,status,content,finished_at,
                        client_request_id,original_assistant_message_id)
                    VALUES (:user,:session,:root,'USER','COMPLETED','Tiếng Việt',CURRENT_TIMESTAMP,:request,:assistant)
                    """).param("user", user).param("assistant", assistant).param("session", session)
                    .param("root", root).param("request", UUID.randomUUID()).update();
            jdbc.sql("""
                    INSERT INTO chat_message(id,session_id,parent_message_id,role,status,content,finished_at,deadline_at)
                    VALUES (:assistant,:session,:user,'ASSISTANT','COMPLETED','Preserved answer',CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + interval '1 minute')
                    """).param("assistant", assistant).param("session", session).param("user", user).update();
            jdbc.sql("""
                    UPDATE chat_message SET requested_model_configuration_id=:model,selected_model_configuration_id=:model
                    WHERE id=:id
                    """).param("model", installed).param("id", assistant).update();
        });
        jdbc.sql("UPDATE model_configuration SET revision=4 WHERE id=:id").param("id", installed).update();
        var preserved = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("llm_provider", "llm_provider_group", "llm_provider_persona", "chat_model_default",
                "persona", "chat_session")) {
            preserved.put(table, jdbc.sql("SELECT * FROM " + table).query().listOfRows());
        }
        // The baseline stops at V53, the last migration before the tokenizer-profile backfill.
        var preservedMessages = jdbc.sql("SELECT id,content,status FROM chat_message").query().listOfRows();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("54").load().migrate();
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
        assertEquals(Set.copyOf(preservedMessages),
                Set.copyOf(jdbc.sql("SELECT id,content,status FROM chat_message").query().listOfRows()));
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
