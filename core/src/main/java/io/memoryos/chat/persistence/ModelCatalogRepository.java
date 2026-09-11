package io.memoryos.chat.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ModelSettings;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.data.domain.PageRequest;

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

    public record Provider(UUID id, UUID tenantId, String name, String adapterType, String baseUrl,
                           boolean enabled, boolean isPublic, @JsonIgnore @Nullable String credential,
                           long revision, Set<UUID> groupIds, Set<UUID> personaIds) {
        @Override public @NonNull String toString() { return "LLMProvider[id=" + id + ", revision=" + revision + "]"; }
    }
    public record Model(UUID id, UUID tenantId, UUID providerId, String modelName, String displayName,
                        boolean visible, ModelSettings settings, long revision) {}
    public record Default(@Nullable UUID modelConfigurationId, long revision) {}
    public record PersonaModel(UUID personaId, @Nullable UUID modelConfigurationId, long revision) {}

    public boolean initialize(UUID tenant) {
        return jdbc.sql("INSERT INTO chat_model_default(tenant_id) VALUES (:tenant) ON CONFLICT DO NOTHING")
                .param("tenant", tenant).update() == 1;
    }

    public List<Provider> providers(UUID tenant) {
        return providers.findByTenantIdOrderByNameAscIdAsc(tenant, PageRequest.of(0, 64)).stream().map(ModelCatalogRepository::provider).toList();
    }
    public Optional<Provider> provider(UUID tenant, UUID id) {
        return providers.findByTenantIdAndId(tenant, id).map(ModelCatalogRepository::provider);
    }
    public void insertProvider(Provider p, @Nullable String builtinKey) {
        var entity = new LlmProviderEntity(p.id(), p.tenantId(), builtinKey, p.adapterType());
        entity.update(p.name(), p.baseUrl(), p.enabled(), p.isPublic(), p.credential(), p.groupIds(), p.personaIds());
        providers.saveAndFlush(entity);
    }
    public void updateProvider(Provider p) {
        var entity = providers.findByTenantIdAndId(p.tenantId(), p.id()).orElseThrow(ChatException::unavailable);
        if (entity.revision() != p.revision()) throw ChatException.conflict();
        entity.update(p.name(), p.baseUrl(), p.enabled(), p.isPublic(), p.credential(), p.groupIds(), p.personaIds());
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
    public List<Model> models(UUID tenant) {
        return models.findByTenantIdOrderByDisplayNameAscIdAsc(tenant, PageRequest.of(0, 256)).stream().map(ModelCatalogRepository::model).toList();
    }
    public Optional<Model> model(UUID tenant, UUID id) {
        return models.findByTenantIdAndId(tenant, id).map(ModelCatalogRepository::model);
    }
    public void insertModel(Model m) {
        var entity = new ModelConfigurationEntity(m.id(), m.tenantId(), m.providerId());
        entity.update(m.modelName(), m.displayName(), m.visible(), JSON.writeValueAsString(m.settings()));
        models.saveAndFlush(entity);
    }
    public void updateModel(Model m) {
        var entity = models.findByTenantIdAndId(m.tenantId(), m.id()).orElseThrow(ChatException::unavailable);
        if (entity.revision() != m.revision()) throw ChatException.conflict();
        entity.update(m.modelName(), m.displayName(), m.visible(), JSON.writeValueAsString(m.settings()));
        models.flush();
    }
    public Default defaultModel(UUID tenant) {
        var entity = defaults.findById(tenant).orElseThrow(ChatException::unavailable);
        return new Default(entity.modelId(), entity.revision());
    }
    public void setDefault(UUID tenant, UUID model, long revision) {
        var entity = defaults.findById(tenant).orElseThrow(ChatException::unavailable);
        if (entity.revision() != revision) throw ChatException.conflict();
        entity.select(model); defaults.flush();
    }
    public PersonaModel personaModel(UUID tenant, UUID persona) {
        return jdbc.sql("SELECT id, model_configuration_id, model_revision FROM persona WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant).param("id", persona)
                .query((r, ignored) -> new PersonaModel(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getLong(3)))
                .optional().orElseThrow(ChatException::unavailable);
    }
    public void setPersonaModel(UUID tenant, UUID persona, @Nullable UUID model, long revision) {
        requireChanged(jdbc.sql("UPDATE persona SET model_configuration_id=:model, model_revision=model_revision+1, revision=revision+1 WHERE tenant_id=:tenant AND id=:persona AND model_revision=:revision")
                .param("tenant", tenant).param("persona", persona).param("model", model, Types.OTHER).param("revision", revision).update());
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
    private static Provider provider(LlmProviderEntity p) {
        return new Provider(p.getId(), p.tenantId(), p.name(), p.adapterType(), p.baseUrl(), p.enabled(), p.publicAccess(),
                p.credential(), p.revision(), p.groupIds(), p.personaIds());
    }
    private static Model model(ModelConfigurationEntity m) {
        return new Model(m.getId(), m.tenantId(), m.providerId(), m.modelName(), m.displayName(), m.visible(),
                JSON.readValue(m.settings(), ModelSettings.class), m.revision());
    }
    private static void requireChanged(int count) { if (count != 1) throw ChatException.conflict(); }
}
