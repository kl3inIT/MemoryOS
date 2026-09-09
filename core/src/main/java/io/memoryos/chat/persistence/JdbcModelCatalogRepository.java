package io.memoryos.chat.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ModelSettings;
import java.sql.ResultSet;
import java.sql.SQLException;
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

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcModelCatalogRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcClient jdbc;
    public JdbcModelCatalogRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

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
        return jdbc.sql("SELECT * FROM llm_provider WHERE tenant_id = :tenant ORDER BY name, id LIMIT 64")
                .param("tenant", tenant).query((r, ignored) -> provider(r)).list();
    }
    public Optional<Provider> provider(UUID tenant, UUID id) {
        return jdbc.sql("SELECT * FROM llm_provider WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant).param("id", id).query((r, ignored) -> provider(r)).optional();
    }
    public void insertProvider(Provider p, @Nullable String builtinKey) {
        jdbc.sql("""
                INSERT INTO llm_provider(id, tenant_id, builtin_key, name, adapter_type, base_url, enabled, is_public, credential)
                VALUES (:id, :tenant, :builtin, :name, :type, :url, :enabled, :public, :credential)
                """).param("id", p.id()).param("tenant", p.tenantId()).param("builtin", builtinKey, Types.VARCHAR)
                .param("name", p.name()).param("type", p.adapterType()).param("url", p.baseUrl())
                .param("enabled", p.enabled()).param("public", p.isPublic()).param("credential", p.credential(), Types.VARCHAR).update();
        associations(p);
    }
    public void updateProvider(Provider p) {
        int changed = jdbc.sql("""
                UPDATE llm_provider SET name=:name, base_url=:url, enabled=:enabled, is_public=:public,
                    credential=:credential, revision=revision+1
                WHERE tenant_id=:tenant AND id=:id AND revision=:revision
                """).param("id", p.id()).param("tenant", p.tenantId()).param("revision", p.revision())
                .param("name", p.name()).param("url", p.baseUrl()).param("enabled", p.enabled()).param("public", p.isPublic())
                .param("credential", p.credential(), Types.VARCHAR).update();
        requireChanged(changed);
        associations(p);
    }
    private void associations(Provider p) {
        jdbc.sql("DELETE FROM llm_provider_group WHERE tenant_id=:tenant AND provider_id=:id")
                .param("tenant", p.tenantId()).param("id", p.id()).update();
        jdbc.sql("DELETE FROM llm_provider_persona WHERE tenant_id=:tenant AND provider_id=:id")
                .param("tenant", p.tenantId()).param("id", p.id()).update();
        for (var id : p.groupIds()) jdbc.sql("INSERT INTO llm_provider_group VALUES (:tenant, :provider, :id)")
                .param("tenant", p.tenantId()).param("provider", p.id()).param("id", id).update();
        for (var id : p.personaIds()) jdbc.sql("INSERT INTO llm_provider_persona VALUES (:tenant, :provider, :id)")
                .param("tenant", p.tenantId()).param("provider", p.id()).param("id", id).update();
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
        return jdbc.sql("SELECT * FROM model_configuration WHERE tenant_id=:tenant ORDER BY display_name, id LIMIT 256")
                .param("tenant", tenant).query(JdbcModelCatalogRepository::model).list();
    }
    public Optional<Model> model(UUID tenant, UUID id) {
        return jdbc.sql("SELECT * FROM model_configuration WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant).param("id", id).query(JdbcModelCatalogRepository::model).optional();
    }
    public void insertModel(Model m) {
        jdbc.sql("""
                INSERT INTO model_configuration(id, tenant_id, provider_id, model_name, display_name, visible, settings)
                VALUES (:id, :tenant, :provider, :model, :name, :visible, CAST(:settings AS jsonb))
                """).param("id", m.id()).param("tenant", m.tenantId()).param("provider", m.providerId())
                .param("model", m.modelName()).param("name", m.displayName()).param("visible", m.visible())
                .param("settings", JSON.writeValueAsString(m.settings())).update();
    }
    public void updateModel(Model m) {
        requireChanged(jdbc.sql("""
                UPDATE model_configuration SET model_name=:model, display_name=:name, visible=:visible,
                    settings=CAST(:settings AS jsonb), revision=revision+1
                WHERE tenant_id=:tenant AND id=:id AND revision=:revision
                """).param("id", m.id()).param("tenant", m.tenantId()).param("revision", m.revision())
                .param("model", m.modelName()).param("name", m.displayName()).param("visible", m.visible())
                .param("settings", JSON.writeValueAsString(m.settings())).update());
    }
    public Default defaultModel(UUID tenant) {
        return jdbc.sql("SELECT model_configuration_id, revision FROM chat_model_default WHERE tenant_id=:tenant")
                .param("tenant", tenant).query((r, ignored) -> new Default(r.getObject(1, UUID.class), r.getLong(2))).single();
    }
    public void setDefault(UUID tenant, UUID model, long revision) {
        requireChanged(jdbc.sql("UPDATE chat_model_default SET model_configuration_id=:model, revision=revision+1 WHERE tenant_id=:tenant AND revision=:revision")
                .param("tenant", tenant).param("model", model).param("revision", revision).update());
    }
    public PersonaModel personaModel(UUID tenant, UUID persona) {
        return jdbc.sql("SELECT id, model_configuration_id, model_revision FROM persona WHERE tenant_id=:tenant AND id=:id")
                .param("tenant", tenant).param("id", persona)
                .query((r, ignored) -> new PersonaModel(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getLong(3)))
                .optional().orElseThrow(ChatException::unavailable);
    }
    public void setPersonaModel(UUID tenant, UUID persona, @Nullable UUID model, long revision) {
        requireChanged(jdbc.sql("UPDATE persona SET model_configuration_id=:model, model_revision=model_revision+1 WHERE tenant_id=:tenant AND id=:persona AND model_revision=:revision")
                .param("tenant", tenant).param("persona", persona).param("model", model, Types.OTHER).param("revision", revision).update());
    }
    public void deleteModel(UUID tenant, UUID model, long revision) {
        jdbc.sql("UPDATE persona SET model_configuration_id=NULL, model_revision=model_revision+1 WHERE tenant_id=:tenant AND model_configuration_id=:model")
                .param("tenant", tenant).param("model", model).update();
        requireChanged(jdbc.sql("DELETE FROM model_configuration WHERE tenant_id=:tenant AND id=:id AND revision=:revision")
                .param("tenant", tenant).param("id", model).param("revision", revision).update());
    }
    public void deleteProvider(UUID tenant, UUID provider, long revision) {
        jdbc.sql("""
                UPDATE persona SET model_configuration_id=NULL, model_revision=model_revision+1
                WHERE tenant_id=:tenant AND model_configuration_id IN
                    (SELECT id FROM model_configuration WHERE tenant_id=:tenant AND provider_id=:provider)
                """).param("tenant", tenant).param("provider", provider).update();
        requireChanged(jdbc.sql("DELETE FROM llm_provider WHERE tenant_id=:tenant AND id=:id AND revision=:revision")
                .param("tenant", tenant).param("id", provider).param("revision", revision).update());
    }
    private Provider provider(ResultSet r) throws SQLException {
        UUID id = r.getObject("id", UUID.class);
        UUID tenant = r.getObject("tenant_id", UUID.class);
        var groups = jdbc.sql("SELECT group_id FROM llm_provider_group WHERE tenant_id=:tenant AND provider_id=:id")
                .param("tenant", tenant).param("id", id).query(UUID.class).list();
        var personas = jdbc.sql("SELECT persona_id FROM llm_provider_persona WHERE tenant_id=:tenant AND provider_id=:id")
                .param("tenant", tenant).param("id", id).query(UUID.class).list();
        return new Provider(id, tenant, r.getString("name"), r.getString("adapter_type"), r.getString("base_url"),
                r.getBoolean("enabled"), r.getBoolean("is_public"), r.getString("credential"), r.getLong("revision"),
                Set.copyOf(groups), Set.copyOf(personas));
    }
    private static Model model(ResultSet r, int n) throws SQLException {
        return new Model(r.getObject("id", UUID.class), r.getObject("tenant_id", UUID.class), r.getObject("provider_id", UUID.class),
                r.getString("model_name"), r.getString("display_name"), r.getBoolean("visible"),
                JSON.readValue(r.getString("settings"), ModelSettings.class), r.getLong("revision"));
    }
    private static void requireChanged(int count) { if (count != 1) throw ChatException.conflict(); }
}
