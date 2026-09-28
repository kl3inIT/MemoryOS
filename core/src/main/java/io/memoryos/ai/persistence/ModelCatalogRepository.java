package io.memoryos.ai.persistence;

import io.memoryos.ai.AiException;
import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.FlowModelDefault;
import io.memoryos.ai.LlmProvider;
import io.memoryos.ai.ModelConfiguration;
import io.memoryos.ai.ModelDefault;
import io.memoryos.ai.ModelFlow;
import io.memoryos.ai.ModelSettings;
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
                .optional().orElseThrow(AiException::unavailable);
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
        var entity = providers.findByTenantIdAndId(p.tenantId(), p.id()).orElseThrow(AiException::unavailable);
        if (entity.revision() != p.revision()) throw AiException.conflict();
        entity.update(p.name(), p.baseUrl(), p.enabled(), p.isPublic(), p.credential(), p.groupIds(), p.personaIds(), p.dataBoundary());
        providers.flush();
    }
    public boolean groupsExist(UUID tenant, Set<UUID> groups) {
        long groupCount = groups.isEmpty() ? 0 : jdbc.sql("SELECT count(*) FROM iam_groups WHERE tenant_id=:tenant AND id IN (:ids)")
                .param("tenant", tenant).param("ids", groups).query(Long.class).single();
        return groupCount == groups.size();
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
        var entity = models.findByTenantIdAndId(m.tenantId(), m.id()).orElseThrow(AiException::unavailable);
        if (entity.revision() != m.revision()) throw AiException.conflict();
        entity.update(m.modelName(), m.displayName(), m.visible(), JSON.writeValueAsString(m.settings()));
        models.flush();
    }
    public ModelDefault defaultModel(UUID tenant) {
        var entity = defaults.findById(tenant).orElseThrow(AiException::unavailable);
        return new ModelDefault(entity.modelId(), entity.revision());
    }
    public void setDefault(UUID tenant, UUID model, long revision) {
        var entity = defaults.findById(tenant).orElseThrow(AiException::unavailable);
        if (entity.revision() != revision) throw AiException.conflict();
        entity.select(model); defaults.flush();
    }
    public void deleteModel(UUID tenant, UUID model, long revision) {
        var entity = models.findByTenantIdAndId(tenant, model).orElseThrow(AiException::unavailable);
        if (entity.revision() != revision) throw AiException.conflict();
        models.delete(entity); models.flush();
    }
    public void deleteProvider(UUID tenant, UUID provider, long revision) {
        var entity = providers.findByTenantIdAndId(tenant, provider).orElseThrow(AiException::unavailable);
        if (entity.revision() != revision) throw AiException.conflict();
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
    private static void requireChanged(int count) { if (count != 1) throw AiException.conflict(); }
}
