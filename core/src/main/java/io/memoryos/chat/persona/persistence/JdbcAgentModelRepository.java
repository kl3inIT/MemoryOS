package io.memoryos.chat.persona.persistence;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.PersonaModelDefault;
import io.memoryos.chat.PersonaSummary;
import java.sql.Types;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The catalog model each agent runs on and each member's personal default model; the catalog itself belongs to AI. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcAgentModelRepository {
    private final JdbcClient jdbc;

    public JdbcAgentModelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
                .query((row, _) -> new PersonaSummary(row.getObject("id", UUID.class), row.getString("name"))).list();
    }

    /** Whether every id names an agent of this Tenant, deleted or not, as a provider restriction may. */
    public boolean exist(UUID tenant, Set<UUID> personas) {
        long count = personas.isEmpty() ? 0 : jdbc.sql("SELECT count(*) FROM persona WHERE tenant_id=:tenant AND id IN (:ids)")
                .param("tenant", tenant).param("ids", personas).query(Long.class).single();
        return count == personas.size();
    }

    /** The member's personal default model (MEM-145), or null. */
    public @Nullable UUID personalDefault(UUID tenant, UUID actor) {
        return jdbc.sql("SELECT default_model_configuration_id FROM chat_preferences WHERE tenant_id=:tenant AND actor_id=:actor")
                .param("tenant", tenant).param("actor", actor).query(UUID.class).optional().orElse(null);
    }

    public PersonaModelDefault personaModel(UUID tenant, UUID actor, boolean agentsManage, UUID persona) {
        return jdbc.sql("SELECT p.id, p.model_configuration_id, p.model_revision FROM persona p WHERE p.tenant_id=:tenant AND p.id=:id "
                        + "AND p.deleted_at IS NULL AND " + AgentAccessSql.USES)
                .param("tenant", tenant).param("actor", actor).param("agentsManage", agentsManage).param("id", persona)
                .query((r, ignored) -> new PersonaModelDefault(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getLong(3)))
                .optional().orElseThrow(ChatException::unavailable);
    }

    public void setPersonaModel(UUID tenant, UUID actor, boolean agentsManage, UUID persona, @Nullable UUID model, long revision) {
        int changed = jdbc.sql("""
                UPDATE persona p SET model_configuration_id=:model, model_revision=model_revision+1, revision=revision+1
                WHERE p.tenant_id=:tenant AND p.id=:persona AND p.model_revision=:revision AND p.deleted_at IS NULL
                  AND (p.builtin_key IS NOT NULL OR """ + AgentAccessSql.EDITS + ")").param("tenant", tenant).param("persona", persona)
                .param("actor", actor).param("agentsManage", agentsManage)
                .param("model", model, Types.OTHER).param("revision", revision).update();
        if (changed != 1) throw ChatException.conflict();
    }

    /** Agents that ran on one of these models fall back to the member's or the Tenant's default. */
    public void clearModels(UUID tenant, Set<UUID> models) {
        if (models.isEmpty()) return;
        jdbc.sql("""
                UPDATE persona SET model_configuration_id=NULL, model_revision=model_revision+1, revision=revision+1
                WHERE tenant_id=:tenant AND model_configuration_id IN (:models)
                """).param("tenant", tenant).param("models", models).update();
    }
}
