package io.memoryos.chat.persistence;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.DataBoundary;
import io.memoryos.chat.catalog.FlowModelDefault;
import io.memoryos.chat.catalog.LlmProvider;
import io.memoryos.chat.catalog.ModelConfiguration;
import io.memoryos.chat.catalog.ModelDefault;
import io.memoryos.chat.catalog.ModelFlow;
import io.memoryos.chat.catalog.ModelSettings;
import io.memoryos.chat.catalog.PersonaModelDefault;
import io.memoryos.chat.catalog.PersonaSummary;
import java.sql.Types;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class ModelCatalogRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcClient jdbc;
    private final JpaLlmProviderRepository providers;
    private final JpaModelConfigurationRepository models;
    private final JpaChatModelDefaultRepository defaults;
    public ModelCatalogRepository(JdbcClient jdbc, JpaLlmProviderRepository providers,
                                  JpaModelConfigurationRepository models, JpaChatModelDefaultRepository defaults) {
        this.jdbc = jdbc; this.providers = providers; this.models = models; this.defaults = defaults;
    }

    /** Agents the model administrator can use (Onyx lists personas the administrator may see). */
    public boolean personaExists(UUID tenant, UUID actor, UUID persona, boolean agentsManage) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM persona p WHERE p.tenant_id=:tenant AND p.id=:id AND p.deleted_at IS NULL AND "
                        + AgentAccessSql.USES + ")")
                .param("tenant", tenant).param("actor", actor).param("agentsManage", agentsManage).param("id", persona)
                .query(Boolean.class).single();
    }

    public List<PersonaSummary> personas(UUID tenant, UUID actor, boolean agentsManage, @Nullable UUID after, int limit) {
        return jdbc.sql("SELECT p.id, p.name FROM persona p WHERE p.tenant_id=:tenant AND p.deleted_at IS NULL AND " + AgentAccessSql.USES
                        + (after == null ? "" : " AND p.id > :after") + " ORDER BY p.id LIMIT :limit")
                .param("tenant", tenant).param("actor", actor).param("agentsManage", agentsManage)
                .param("after", after, Types.OTHER).param("limit", limit)
                .query((row, number) -> new PersonaSummary(row.getObject("id", UUID.class), row.getString("name"))).list();
    }

    public boolean initialize(UUID tenant) {
        return jdbc.sql("INSERT INTO chat_model_default(tenant_id) VALUES (:tenant) ON CONFLICT DO NOTHING")
                .param("tenant", tenant).update() == 1;
    }
    /**
     * Seeds one row per flow naming the Tenant's Chat model, so every task names the model that runs it rather than
     * following whatever the conversation model becomes. Call it after the Chat default is set.
     */
    public void initializeFlows(UUID tenant) {
        for (var flow : ModelFlow.values())
            jdbc.sql("""
                    INSERT INTO model_flow_default(tenant_id, flow, model_configuration_id)
                    VALUES (:tenant, :flow,
                            (SELECT model_configuration_id FROM chat_model_default WHERE tenant_id = :tenant))
                    ON CONFLICT DO NOTHING
                    """).param("tenant", tenant).param("flow", flow.name()).update();
    }
    public List<FlowModelDefault> flowDefaults(UUID tenant) {
        return jdbc.sql("SELECT flow, model_configuration_id, revision FROM model_flow_default WHERE tenant_id=:tenant ORDER BY flow")
                .param("tenant", tenant).query((r, ignored) -> new FlowModelDefault(ModelFlow.valueOf(r.getString(1)),
                        r.getObject(2, UUID.class), r.getLong(3))).list();
    }
    public FlowModelDefault flowDefault(UUID tenant, ModelFlow flow) {
        return jdbc.sql("SELECT model_configuration_id, revision FROM model_flow_default WHERE tenant_id=:tenant AND flow=:flow")
                .param("tenant", tenant).param("flow", flow.name())
                .query((r, ignored) -> new FlowModelDefault(flow, r.getObject(1, UUID.class), r.getLong(2)))
                .optional().orElseThrow(ChatException::unavailable);
    }
    public void setFlowDefault(UUID tenant, ModelFlow flow, @Nullable UUID model, long revision) {
        requireChanged(jdbc.sql("UPDATE model_flow_default SET model_configuration_id=:model, revision=revision+1 "
                        + "WHERE tenant_id=:tenant AND flow=:flow AND revision=:revision")
                .param("tenant", tenant).param("flow", flow.name()).param("model", model, Types.OTHER)
                .param("revision", revision).update());
    }

    public List<LlmProvider> providers(UUID tenant) {
        var groups = associationIndex(tenant, "llm_provider_group", "group_id");
        var personas = associationIndex(tenant, "llm_provider_persona", "persona_id");
        return jdbc.sql("SELECT * FROM llm_provider WHERE tenant_id=:tenant ORDER BY name, id LIMIT 64")
                .param("tenant", tenant).query((r, ignored) -> {
                    UUID id = r.getObject("id", UUID.class);
                    return new LlmProvider(id, r.getObject("tenant_id", UUID.class), r.getString("name"), r.getString("adapter_type"),
                            r.getString("base_url"), r.getBoolean("enabled"), r.getBoolean("is_public"), r.getString("credential"),
                            r.getLong("revision"), Set.copyOf(groups.getOrDefault(id, Set.of())), Set.copyOf(personas.getOrDefault(id, Set.of())),
                            DataBoundary.valueOf(r.getString("data_boundary")));
                }).list();
    }
    private Map<UUID, Set<UUID>> associationIndex(UUID tenant, String table, String column) {
        var index = new HashMap<UUID, Set<UUID>>();
        // Identifiers are internal constants; Tenant values remain bound parameters.
        jdbc.sql("SELECT provider_id, " + column + " FROM " + table + " WHERE tenant_id=:tenant")
                .param("tenant", tenant).query((r, ignored) -> {
                    index.computeIfAbsent(r.getObject(1, UUID.class), _ -> new HashSet<>()).add(r.getObject(2, UUID.class));
                    return r.getObject(1, UUID.class);
                }).list();
        return index;
    }
    public Optional<LlmProvider> provider(UUID tenant, UUID id) {
        return providers.findByTenantIdAndId(tenant, id).map(ModelCatalogRepository::provider);
    }
    public void insertProvider(LlmProvider p, @Nullable String builtinKey) {
        var entity = new LlmProviderEntity(p.id(), p.tenantId(), builtinKey, p.adapterType());
        entity.update(p.name(), p.baseUrl(), p.enabled(), p.isPublic(), p.credential(), p.groupIds(), p.personaIds(), p.dataBoundary());
        providers.saveAndFlush(entity);
    }
    public void updateProvider(LlmProvider p) {
        var entity = providers.findByTenantIdAndId(p.tenantId(), p.id()).orElseThrow(ChatException::unavailable);
        if (entity.revision() != p.revision()) throw ChatException.conflict();
        entity.update(p.name(), p.baseUrl(), p.enabled(), p.isPublic(), p.credential(), p.groupIds(), p.personaIds(), p.dataBoundary());
        providers.flush();
    }
    public boolean associationsExist(UUID tenant, Set<UUID> groups, Set<UUID> personas) {
        long groupCount = groups.isEmpty() ? 0 : jdbc.sql("SELECT count(*) FROM iam_groups WHERE tenant_id=:tenant AND id IN (:ids)")
                .param("tenant", tenant).param("ids", groups).query(Long.class).single();
        long personaCount = personas.isEmpty() ? 0 : jdbc.sql("SELECT count(*) FROM persona WHERE tenant_id=:tenant AND id IN (:ids)")
                .param("tenant", tenant).param("ids", personas).query(Long.class).single();
        return groupCount == groups.size() && personaCount == personas.size();
    }
    public Set<UUID> actorGroups(UUID tenant, UUID actor) {
        return Set.copyOf(jdbc.sql("SELECT group_id FROM iam_group_memberships WHERE tenant_id=:tenant AND actor_id=:actor")
                .param("tenant", tenant).param("actor", actor).query(UUID.class).list());
    }
    public List<ModelConfiguration> models(UUID tenant) {
        return models.findByTenantIdOrderByDisplayNameAscIdAsc(tenant, PageRequest.of(0, 256)).stream().map(ModelCatalogRepository::model).toList();
    }
    public Optional<ModelConfiguration> model(UUID tenant, UUID id) {
        return models.findByTenantIdAndId(tenant, id).map(ModelCatalogRepository::model);
    }
    public void insertModel(ModelConfiguration m) {
        var entity = new ModelConfigurationEntity(m.id(), m.tenantId(), m.providerId());
        entity.update(m.modelName(), m.displayName(), m.visible(), JSON.writeValueAsString(m.settings()));
        models.saveAndFlush(entity);
    }
    public void updateModel(ModelConfiguration m) {
        var entity = models.findByTenantIdAndId(m.tenantId(), m.id()).orElseThrow(ChatException::unavailable);
        if (entity.revision() != m.revision()) throw ChatException.conflict();
        entity.update(m.modelName(), m.displayName(), m.visible(), JSON.writeValueAsString(m.settings()));
        models.flush();
    }
    public ModelDefault defaultModel(UUID tenant) {
        var entity = defaults.findById(tenant).orElseThrow(ChatException::unavailable);
        return new ModelDefault(entity.modelId(), entity.revision());
    }
    /** The member's personal default model (MEM-145), or null. */
    public @Nullable UUID personalDefault(UUID tenant, UUID actor) {
        return jdbc.sql("SELECT default_model_configuration_id FROM chat_preferences WHERE tenant_id=:tenant AND actor_id=:actor")
                .param("tenant", tenant).param("actor", actor).query(UUID.class).optional().orElse(null);
    }
    public void setDefault(UUID tenant, UUID model, long revision) {
        var entity = defaults.findById(tenant).orElseThrow(ChatException::unavailable);
        if (entity.revision() != revision) throw ChatException.conflict();
        entity.select(model); defaults.flush();
    }
    public PersonaModelDefault personaModel(UUID tenant, UUID actor, boolean agentsManage, UUID persona) {
        return jdbc.sql("SELECT p.id, p.model_configuration_id, p.model_revision FROM persona p WHERE p.tenant_id=:tenant AND p.id=:id "
                        + "AND p.deleted_at IS NULL AND " + AgentAccessSql.USES)
                .param("tenant", tenant).param("actor", actor).param("agentsManage", agentsManage).param("id", persona)
                .query((r, ignored) -> new PersonaModelDefault(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getLong(3)))
                .optional().orElseThrow(ChatException::unavailable);
    }
    public void setPersonaModel(UUID tenant, UUID actor, boolean agentsManage, UUID persona, @Nullable UUID model, long revision) {
        requireChanged(jdbc.sql("""
                UPDATE persona p SET model_configuration_id=:model, model_revision=model_revision+1, revision=revision+1
                WHERE p.tenant_id=:tenant AND p.id=:persona AND p.model_revision=:revision AND p.deleted_at IS NULL
                  AND (p.builtin_key IS NOT NULL OR """ + AgentAccessSql.EDITS + ")").param("tenant", tenant).param("persona", persona)
                .param("actor", actor).param("agentsManage", agentsManage)
                .param("model", model, Types.OTHER).param("revision", revision).update());
    }
    public void deleteModel(UUID tenant, UUID model, long revision) {
        var entity = models.findByTenantIdAndId(tenant, model).orElseThrow(ChatException::unavailable);
        if (entity.revision() != revision) throw ChatException.conflict();
        jdbc.sql("UPDATE persona SET model_configuration_id=NULL, model_revision=model_revision+1, revision=revision+1 WHERE tenant_id=:tenant AND model_configuration_id=:model")
                .param("tenant", tenant).param("model", model).update();
        models.delete(entity); models.flush();
    }
    public void deleteProvider(UUID tenant, UUID provider, long revision) {
        var entity = providers.findByTenantIdAndId(tenant, provider).orElseThrow(ChatException::unavailable);
        if (entity.revision() != revision) throw ChatException.conflict();
        jdbc.sql("""
                UPDATE persona SET model_configuration_id=NULL, model_revision=model_revision+1, revision=revision+1
                WHERE tenant_id=:tenant AND model_configuration_id IN
                    (SELECT id FROM model_configuration WHERE tenant_id=:tenant AND provider_id=:provider)
                """).param("tenant", tenant).param("provider", provider).update();
        providers.delete(entity); providers.flush();
    }
    private static LlmProvider provider(LlmProviderEntity p) {
        return new LlmProvider(p.getId(), p.tenantId(), p.name(), p.adapterType(), p.baseUrl(), p.enabled(), p.publicAccess(),
                p.credential(), p.revision(), p.groupIds(), p.personaIds(), p.dataBoundary());
    }
    private static ModelConfiguration model(ModelConfigurationEntity m) {
        return new ModelConfiguration(m.getId(), m.tenantId(), m.providerId(), m.modelName(), m.displayName(), m.visible(),
                JSON.readValue(m.settings(), ModelSettings.class), m.revision());
    }
    private static void requireChanged(int count) { if (count != 1) throw ChatException.conflict(); }
}
