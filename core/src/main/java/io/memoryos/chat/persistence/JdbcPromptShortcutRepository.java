package io.memoryos.chat.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Onyx {@code InputPrompt}: private shortcuts per Actor and public Tenant shortcuts an Actor may hide. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcPromptShortcutRepository {
    public record PromptShortcut(UUID id, String name, String content, boolean active, boolean isPublic, boolean hidden, long revision) {}

    private final JdbcClient jdbc;

    public JdbcPromptShortcutRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Own shortcuts plus public ones; hidden public shortcuts are returned only when requested. */
    public List<PromptShortcut> visible(UUID tenant, UUID actor, boolean includeHidden) {
        return jdbc.sql("""
                        SELECT s.id, s.name, s.content, s.active, s.owner_actor_id IS NULL AS is_public, s.revision,
                               EXISTS (SELECT 1 FROM prompt_shortcut_hidden h WHERE h.tenant_id = s.tenant_id
                                       AND h.shortcut_id = s.id AND h.actor_id = :actor) AS hidden
                        FROM prompt_shortcut s
                        WHERE s.tenant_id = :tenant AND (s.owner_actor_id = :actor OR s.owner_actor_id IS NULL)
                        ORDER BY s.owner_actor_id NULLS LAST, lower(s.name), s.id
                        """).param("tenant", tenant).param("actor", actor)
                .query((row, ignored) -> new PromptShortcut(row.getObject("id", UUID.class), row.getString("name"), row.getString("content"),
                        row.getBoolean("active"), row.getBoolean("is_public"), row.getBoolean("hidden"), row.getLong("revision")))
                .list().stream().filter(shortcut -> includeHidden || !shortcut.hidden()).toList();
    }

    public List<PromptShortcut> publicShortcuts(UUID tenant) {
        return jdbc.sql("""
                        SELECT id, name, content, active, revision FROM prompt_shortcut
                        WHERE tenant_id = :tenant AND owner_actor_id IS NULL ORDER BY lower(name), id
                        """).param("tenant", tenant)
                .query((row, ignored) -> new PromptShortcut(row.getObject("id", UUID.class), row.getString("name"), row.getString("content"),
                        row.getBoolean("active"), true, false, row.getLong("revision"))).list();
    }

    /** Locks one shortcut owned by the Actor, or a public shortcut when {@code owner} is null. */
    public Optional<PromptShortcut> locked(UUID tenant, @Nullable UUID owner, UUID id) {
        return jdbc.sql("""
                        SELECT id, name, content, active, owner_actor_id IS NULL AS is_public, revision FROM prompt_shortcut
                        WHERE tenant_id = :tenant AND id = :id AND owner_actor_id IS NOT DISTINCT FROM :owner FOR UPDATE
                        """).param("tenant", tenant).param("id", id).param("owner", owner, java.sql.Types.OTHER)
                .query((row, ignored) -> new PromptShortcut(row.getObject("id", UUID.class), row.getString("name"), row.getString("content"),
                        row.getBoolean("active"), row.getBoolean("is_public"), false, row.getLong("revision"))).optional();
    }

    public boolean publicExists(UUID tenant, UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM prompt_shortcut WHERE tenant_id = :tenant AND id = :id AND owner_actor_id IS NULL)")
                .param("tenant", tenant).param("id", id).query(Boolean.class).single();
    }

    public boolean nameTaken(UUID tenant, @Nullable UUID owner, String name, @Nullable UUID except) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM prompt_shortcut WHERE tenant_id = :tenant
                          AND owner_actor_id IS NOT DISTINCT FROM :owner AND lower(name) = lower(:name) AND id IS DISTINCT FROM :except)
                        """).param("tenant", tenant).param("owner", owner, java.sql.Types.OTHER).param("name", name)
                .param("except", except, java.sql.Types.OTHER).query(Boolean.class).single();
    }

    public int countOwned(UUID tenant, UUID owner) {
        return jdbc.sql("SELECT count(*) FROM prompt_shortcut WHERE tenant_id = :tenant AND owner_actor_id = :owner")
                .param("tenant", tenant).param("owner", owner).query(Integer.class).single();
    }

    public void insert(UUID tenant, @Nullable UUID owner, UUID id, String name, String content, boolean active) {
        jdbc.sql("""
                        INSERT INTO prompt_shortcut (tenant_id, id, owner_actor_id, name, content, active)
                        VALUES (:tenant, :id, :owner, :name, :content, :active)
                        """).param("tenant", tenant).param("id", id).param("owner", owner, java.sql.Types.OTHER)
                .param("name", name).param("content", content).param("active", active).update();
    }

    public void update(UUID tenant, UUID id, String name, String content, boolean active) {
        jdbc.sql("""
                        UPDATE prompt_shortcut SET name = :name, content = :content, active = :active, revision = revision + 1
                        WHERE tenant_id = :tenant AND id = :id
                        """).param("tenant", tenant).param("id", id).param("name", name).param("content", content)
                .param("active", active).update();
    }

    public void delete(UUID tenant, UUID id) {
        jdbc.sql("DELETE FROM prompt_shortcut WHERE tenant_id = :tenant AND id = :id").param("tenant", tenant).param("id", id).update();
    }

    public void hide(UUID tenant, UUID id, UUID actor, boolean hidden) {
        if (hidden) jdbc.sql("""
                        INSERT INTO prompt_shortcut_hidden (tenant_id, shortcut_id, actor_id) VALUES (:tenant, :id, :actor)
                        ON CONFLICT DO NOTHING
                        """).param("tenant", tenant).param("id", id).param("actor", actor).update();
        else jdbc.sql("DELETE FROM prompt_shortcut_hidden WHERE tenant_id = :tenant AND shortcut_id = :id AND actor_id = :actor")
                .param("tenant", tenant).param("id", id).param("actor", actor).update();
    }

    public boolean shortcutsEnabled(UUID tenant, UUID actor) {
        return jdbc.sql("SELECT shortcuts_enabled FROM actor_agent_preferences WHERE tenant_id = :tenant AND actor_id = :actor")
                .param("tenant", tenant).param("actor", actor).query(Boolean.class).optional().orElse(true);
    }

    public void shortcutsEnabled(UUID tenant, UUID actor, boolean enabled) {
        jdbc.sql("""
                        INSERT INTO actor_agent_preferences (tenant_id, actor_id, shortcuts_enabled) VALUES (:tenant, :actor, :enabled)
                        ON CONFLICT (tenant_id, actor_id) DO UPDATE SET shortcuts_enabled = EXCLUDED.shortcuts_enabled,
                            revision = actor_agent_preferences.revision + 1
                        """).param("tenant", tenant).param("actor", actor).param("enabled", enabled).update();
    }
}
