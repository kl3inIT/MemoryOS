package io.memoryos.chat.persistence;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Agent relations (shares, tools, MCP servers, labels, pins) and authority reads over {@link AgentAccessSql}. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcAgentRepository {
    public enum Permission { VIEWER, EDITOR }
    public enum View { ALL, MINE, SHARED }

    public record Access(UUID id, boolean uses, boolean edits, boolean owns, boolean vacant) {}
    public record AgentRef(UUID id, String name) {}
    public record AgentPerson(UUID actorId, @Nullable String name, @Nullable String email) {}
    public record AgentUserShare(AgentPerson person, Permission permission) {}
    public record AgentGroupShare(AgentRef group, Permission permission) {}
    public record AgentOwner(@Nullable AgentPerson actor, @Nullable AgentRef group) {}
    public record Details(Map<UUID, Set<String>> tools, Map<UUID, List<AgentRef>> mcpServers, Map<UUID, List<AgentRef>> labels,
                          Map<UUID, AgentOwner> owners, Map<UUID, List<AgentUserShare>> userShares, Map<UUID, List<AgentGroupShare>> groupShares,
                          Set<UUID> pinned) {}
    public record AgentShareOptions(List<AgentPerson> people, List<AgentRef> groups) {}

    private final JdbcClient jdbc;

    public JdbcAgentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Map<UUID, Access> access(UUID tenant, UUID actor, boolean agentsManage, Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var result = new HashMap<UUID, Access>();
        jdbc.sql("""
                        SELECT p.id, %s AS uses, %s AS edits, %s AS owns, %s AS vacant
                        FROM persona p WHERE p.tenant_id = :tenant AND p.id IN (:ids)
                        """.formatted(AgentAccessSql.USES, AgentAccessSql.EDITS, AgentAccessSql.OWNS, AgentAccessSql.VACANT))
                .param("tenant", tenant).param("actor", actor).param("agentsManage", agentsManage).param("ids", ids)
                .query((row, ignored) -> new Access(row.getObject("id", UUID.class), row.getBoolean("uses"),
                        row.getBoolean("edits"), row.getBoolean("owns"), row.getBoolean("vacant")))
                .list().forEach(access -> result.put(access.id(), access));
        return result;
    }

    /** Usable, non-deleted agents; non-owners only see listed agents, as Onyx. */
    public List<UUID> list(UUID tenant, UUID actor, boolean agentsManage, View view, @Nullable UUID label, @Nullable String query,
                           int offset, int limit) {
        String mine = "(p.builtin_key IS NULL AND (COALESCE(p.owner_actor_id = :actor, FALSE) OR EXISTS (SELECT 1 FROM iam_group_memberships m "
                + "WHERE m.tenant_id = p.tenant_id AND m.group_id = p.owner_group_id AND m.actor_id = :actor)))";
        String scope = switch (view) {
            case ALL -> AgentAccessSql.USES + " AND (p.is_listed OR " + mine + ")";
            case MINE -> mine;
            case SHARED -> AgentAccessSql.USES + " AND p.is_listed AND p.builtin_key IS NULL AND NOT " + mine;
        };
        return jdbc.sql("""
                        SELECT p.id FROM persona p
                        WHERE p.tenant_id = :tenant AND p.deleted_at IS NULL AND %s
                          AND (CAST(:label AS uuid) IS NULL OR EXISTS (SELECT 1 FROM persona_label_assignment a
                               WHERE a.tenant_id = p.tenant_id AND a.persona_id = p.id AND a.label_id = :label))
                          AND (CAST(:query AS text) IS NULL OR p.name ILIKE :pattern OR p.description ILIKE :pattern)
                        ORDER BY p.builtin_key NULLS LAST, p.is_featured DESC, p.display_priority NULLS LAST, lower(p.name), p.id
                        OFFSET :offset LIMIT :limit
                        """.formatted(scope))
                .param("tenant", tenant).param("actor", actor).param("agentsManage", agentsManage)
                .param("label", label, Types.OTHER).param("query", query, Types.VARCHAR)
                .param("pattern", query == null ? null : "%" + escapeLike(query) + "%", Types.VARCHAR)
                .param("offset", offset).param("limit", limit).query(UUID.class).list();
    }

    /** Every custom agent for administrators, including unlisted, deleted and vacant ones. */
    public List<UUID> adminList(UUID tenant, boolean deleted, int offset, int limit) {
        return jdbc.sql("""
                        SELECT p.id FROM persona p WHERE p.tenant_id = :tenant
                          AND (CAST(:deleted AS boolean) OR p.deleted_at IS NULL)
                        ORDER BY p.builtin_key NULLS LAST, p.deleted_at NULLS FIRST,
                                 p.display_priority NULLS LAST, lower(p.name), p.id
                        OFFSET :offset LIMIT :limit
                        """).param("tenant", tenant).param("deleted", deleted).param("offset", offset).param("limit", limit)
                .query(UUID.class).list();
    }

    public Details details(UUID tenant, UUID actor, Collection<UUID> ids) {
        if (ids.isEmpty()) return new Details(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
        var tools = new HashMap<UUID, Set<String>>();
        rows("SELECT persona_id, tool_key FROM persona_tool WHERE tenant_id = :tenant AND persona_id IN (:ids) ORDER BY tool_key",
                tenant, ids).forEach(row -> tools.computeIfAbsent((UUID) row[0], ignored -> new LinkedHashSet<>()).add((String) row[1]));
        var servers = refs("""
                SELECT a.persona_id, s.id, s.name FROM persona_mcp_server a
                JOIN mcp_server s ON s.tenant_id = a.tenant_id AND s.id = a.server_id
                WHERE a.tenant_id = :tenant AND a.persona_id IN (:ids) ORDER BY lower(s.name), s.id
                """, tenant, ids);
        var labels = refs("""
                SELECT a.persona_id, l.id, l.name FROM persona_label_assignment a
                JOIN persona_label l ON l.tenant_id = a.tenant_id AND l.id = a.label_id
                WHERE a.tenant_id = :tenant AND a.persona_id IN (:ids) ORDER BY lower(l.name), l.id
                """, tenant, ids);
        var owners = new HashMap<UUID, AgentOwner>();
        jdbc.sql("""
                        SELECT p.id, p.owner_actor_id, profile.display_name, profile.email, g.id AS group_id, g.name AS group_name
                        FROM persona p
                        LEFT JOIN actor_profiles profile ON profile.actor_id = p.owner_actor_id
                        LEFT JOIN iam_groups g ON g.tenant_id = p.tenant_id AND g.id = p.owner_group_id
                        WHERE p.tenant_id = :tenant AND p.id IN (:ids)
                        """).param("tenant", tenant).param("ids", ids)
                .query((row, ignored) -> {
                    UUID actorId = row.getObject("owner_actor_id", UUID.class), groupId = row.getObject("group_id", UUID.class);
                    owners.put(row.getObject("id", UUID.class), new AgentOwner(
                            actorId == null ? null : new AgentPerson(actorId, row.getString("display_name"), row.getString("email")),
                            groupId == null ? null : new AgentRef(groupId, row.getString("group_name"))));
                    return true;
                }).list();
        var userShares = new HashMap<UUID, List<AgentUserShare>>();
        jdbc.sql("""
                        SELECT s.persona_id, s.actor_id, s.permission, profile.display_name, profile.email
                        FROM persona_user_share s LEFT JOIN actor_profiles profile ON profile.actor_id = s.actor_id
                        WHERE s.tenant_id = :tenant AND s.persona_id IN (:ids)
                        ORDER BY lower(coalesce(profile.display_name, profile.email, '')), s.actor_id
                        """).param("tenant", tenant).param("ids", ids)
                .query((row, ignored) -> userShares.computeIfAbsent(row.getObject("persona_id", UUID.class), key -> new ArrayList<>())
                        .add(new AgentUserShare(new AgentPerson(row.getObject("actor_id", UUID.class), row.getString("display_name"), row.getString("email")),
                                Permission.valueOf(row.getString("permission"))))).list();
        var groupShares = new HashMap<UUID, List<AgentGroupShare>>();
        jdbc.sql("""
                        SELECT s.persona_id, g.id, g.name, s.permission FROM persona_group_share s
                        JOIN iam_groups g ON g.tenant_id = s.tenant_id AND g.id = s.group_id
                        WHERE s.tenant_id = :tenant AND s.persona_id IN (:ids) ORDER BY lower(g.name), g.id
                        """).param("tenant", tenant).param("ids", ids)
                .query((row, ignored) -> groupShares.computeIfAbsent(row.getObject("persona_id", UUID.class), key -> new ArrayList<>())
                        .add(new AgentGroupShare(new AgentRef(row.getObject("id", UUID.class), row.getString("name")),
                                Permission.valueOf(row.getString("permission"))))).list();
        var pinned = Set.copyOf(jdbc.sql("""
                        SELECT persona_id FROM actor_pinned_persona WHERE tenant_id = :tenant AND actor_id = :actor AND persona_id IN (:ids)
                        """).param("tenant", tenant).param("actor", actor).param("ids", ids).query(UUID.class).list());
        return new Details(tools, servers, labels, owners, userShares, groupShares, pinned);
    }

    public void replaceShares(UUID tenant, UUID persona, Map<UUID, Permission> users, Map<UUID, Permission> groups) {
        jdbc.sql("DELETE FROM persona_user_share WHERE tenant_id = :tenant AND persona_id = :persona")
                .param("tenant", tenant).param("persona", persona).update();
        jdbc.sql("DELETE FROM persona_group_share WHERE tenant_id = :tenant AND persona_id = :persona")
                .param("tenant", tenant).param("persona", persona).update();
        users.forEach((actor, permission) -> jdbc.sql("""
                        INSERT INTO persona_user_share (tenant_id, persona_id, actor_id, permission) VALUES (:tenant, :persona, :actor, :permission)
                        """).param("tenant", tenant).param("persona", persona).param("actor", actor).param("permission", permission.name()).update());
        groups.forEach((group, permission) -> jdbc.sql("""
                        INSERT INTO persona_group_share (tenant_id, persona_id, group_id, permission) VALUES (:tenant, :persona, :group, :permission)
                        """).param("tenant", tenant).param("persona", persona).param("group", group).param("permission", permission.name()).update());
    }

    public boolean removeUserShare(UUID tenant, UUID persona, UUID actor) {
        return jdbc.sql("DELETE FROM persona_user_share WHERE tenant_id = :tenant AND persona_id = :persona AND actor_id = :actor")
                .param("tenant", tenant).param("persona", persona).param("actor", actor).update() > 0;
    }

    public void upsertUserShare(UUID tenant, UUID persona, UUID actor, Permission permission) {
        jdbc.sql("""
                        INSERT INTO persona_user_share (tenant_id, persona_id, actor_id, permission) VALUES (:tenant, :persona, :actor, :permission)
                        ON CONFLICT (tenant_id, persona_id, actor_id) DO UPDATE SET permission = EXCLUDED.permission
                        """).param("tenant", tenant).param("persona", persona).param("actor", actor).param("permission", permission.name()).update();
    }

    public void replaceTools(UUID tenant, UUID persona, Set<String> tools, List<UUID> mcpServers) {
        jdbc.sql("DELETE FROM persona_tool WHERE tenant_id = :tenant AND persona_id = :persona")
                .param("tenant", tenant).param("persona", persona).update();
        tools.forEach(tool -> jdbc.sql("INSERT INTO persona_tool (tenant_id, persona_id, tool_key) VALUES (:tenant, :persona, :tool)")
                .param("tenant", tenant).param("persona", persona).param("tool", tool).update());
        jdbc.sql("DELETE FROM persona_mcp_server WHERE tenant_id = :tenant AND persona_id = :persona")
                .param("tenant", tenant).param("persona", persona).update();
        mcpServers.forEach(server -> jdbc.sql("INSERT INTO persona_mcp_server (tenant_id, persona_id, server_id) VALUES (:tenant, :persona, :server)")
                .param("tenant", tenant).param("persona", persona).param("server", server).update());
    }

    public void replaceLabels(UUID tenant, UUID persona, Collection<UUID> labels) {
        jdbc.sql("DELETE FROM persona_label_assignment WHERE tenant_id = :tenant AND persona_id = :persona")
                .param("tenant", tenant).param("persona", persona).update();
        labels.forEach(label -> jdbc.sql("INSERT INTO persona_label_assignment (tenant_id, persona_id, label_id) VALUES (:tenant, :persona, :label)")
                .param("tenant", tenant).param("persona", persona).param("label", label).update());
    }

    public Set<String> tools(UUID tenant, UUID persona) {
        return new LinkedHashSet<>(jdbc.sql("SELECT tool_key FROM persona_tool WHERE tenant_id = :tenant AND persona_id = :persona ORDER BY tool_key")
                .param("tenant", tenant).param("persona", persona).query(String.class).list());
    }

    public List<UUID> mcpServers(UUID tenant, UUID persona) {
        return jdbc.sql("SELECT server_id FROM persona_mcp_server WHERE tenant_id = :tenant AND persona_id = :persona ORDER BY server_id")
                .param("tenant", tenant).param("persona", persona).query(UUID.class).list();
    }

    public int countActiveMembers(UUID tenant, Collection<UUID> actors) {
        if (actors.isEmpty()) return 0;
        return jdbc.sql("SELECT count(*) FROM tenant_memberships WHERE tenant_id = :tenant AND actor_id IN (:actors) AND status = 'ACTIVE'")
                .param("tenant", tenant).param("actors", actors).query(Integer.class).single();
    }

    public int countOrdinaryGroups(UUID tenant, Collection<UUID> groups) {
        if (groups.isEmpty()) return 0;
        return jdbc.sql("SELECT count(*) FROM iam_groups WHERE tenant_id = :tenant AND id IN (:groups) AND system_key IS NULL")
                .param("tenant", tenant).param("groups", groups).query(Integer.class).single();
    }

    public int countMcpServers(UUID tenant, Collection<UUID> servers) {
        if (servers.isEmpty()) return 0;
        return jdbc.sql("SELECT count(*) FROM mcp_server WHERE tenant_id = :tenant AND id IN (:servers)")
                .param("tenant", tenant).param("servers", servers).query(Integer.class).single();
    }

    public boolean memberOf(UUID tenant, UUID actor, UUID group) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM iam_group_memberships WHERE tenant_id = :tenant AND group_id = :group AND actor_id = :actor)")
                .param("tenant", tenant).param("group", group).param("actor", actor).query(Boolean.class).single();
    }

    public AgentShareOptions shareOptions(UUID tenant, @Nullable String query, int limit) {
        String pattern = query == null ? null : "%" + escapeLike(query) + "%";
        var people = jdbc.sql("""
                        SELECT m.actor_id, profile.display_name, profile.email FROM tenant_memberships m
                        LEFT JOIN actor_profiles profile ON profile.actor_id = m.actor_id
                        WHERE m.tenant_id = :tenant AND m.status = 'ACTIVE'
                          AND (CAST(:pattern AS text) IS NULL OR profile.display_name ILIKE :pattern OR profile.email ILIKE :pattern)
                        ORDER BY lower(coalesce(profile.display_name, profile.email, '')), m.actor_id LIMIT :limit
                        """).param("tenant", tenant).param("pattern", pattern, Types.VARCHAR).param("limit", limit)
                .query((row, ignored) -> new AgentPerson(row.getObject("actor_id", UUID.class), row.getString("display_name"), row.getString("email"))).list();
        var groups = jdbc.sql("""
                        SELECT id, name FROM iam_groups WHERE tenant_id = :tenant AND system_key IS NULL
                          AND (CAST(:pattern AS text) IS NULL OR name ILIKE :pattern)
                        ORDER BY lower(name), id LIMIT :limit
                        """).param("tenant", tenant).param("pattern", pattern, Types.VARCHAR).param("limit", limit)
                .query((row, ignored) -> new AgentRef(row.getObject("id", UUID.class), row.getString("name"))).list();
        return new AgentShareOptions(people, groups);
    }

    public List<AgentRef> labels(UUID tenant) {
        return jdbc.sql("SELECT id, name FROM persona_label WHERE tenant_id = :tenant ORDER BY lower(name), id LIMIT 500")
                .param("tenant", tenant).query((row, ignored) -> new AgentRef(row.getObject("id", UUID.class), row.getString("name"))).list();
    }

    public int countLabels(UUID tenant, Collection<UUID> ids) {
        if (ids.isEmpty()) return 0;
        return jdbc.sql("SELECT count(*) FROM persona_label WHERE tenant_id = :tenant AND id IN (:ids)")
                .param("tenant", tenant).param("ids", ids).query(Integer.class).single();
    }

    /** Returns false when the name is already used by another label in the Tenant. */
    public boolean saveLabel(UUID tenant, UUID id, String name) {
        return jdbc.sql("""
                        INSERT INTO persona_label (tenant_id, id, name) VALUES (:tenant, :id, :name)
                        ON CONFLICT (tenant_id, id) DO UPDATE SET name = EXCLUDED.name
                        """).param("tenant", tenant).param("id", id).param("name", name).update() > 0;
    }

    public boolean labelNameTaken(UUID tenant, String name, @Nullable UUID except) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM persona_label WHERE tenant_id = :tenant AND lower(name) = lower(:name) AND id IS DISTINCT FROM :except)")
                .param("tenant", tenant).param("name", name).param("except", except, Types.OTHER).query(Boolean.class).single();
    }

    public boolean labelExists(UUID tenant, UUID id) {
        return countLabels(tenant, List.of(id)) == 1;
    }

    public boolean deleteLabel(UUID tenant, UUID id) {
        return jdbc.sql("DELETE FROM persona_label WHERE tenant_id = :tenant AND id = :id").param("tenant", tenant).param("id", id).update() > 0;
    }

    public List<UUID> pins(UUID tenant, UUID actor) {
        return jdbc.sql("SELECT persona_id FROM actor_pinned_persona WHERE tenant_id = :tenant AND actor_id = :actor ORDER BY position")
                .param("tenant", tenant).param("actor", actor).query(UUID.class).list();
    }

    public void replacePins(UUID tenant, UUID actor, List<UUID> personas) {
        jdbc.sql("DELETE FROM actor_pinned_persona WHERE tenant_id = :tenant AND actor_id = :actor")
                .param("tenant", tenant).param("actor", actor).update();
        for (int position = 0; position < personas.size(); position++) {
            jdbc.sql("INSERT INTO actor_pinned_persona (tenant_id, actor_id, persona_id, position) VALUES (:tenant, :actor, :persona, :position)")
                    .param("tenant", tenant).param("actor", actor).param("persona", personas.get(position)).param("position", position).update();
        }
        markPinsSeeded(tenant, actor);
    }

    /** Onyx {@code seed_pinned_personas_from_featured}: once per Actor, featured public listed agents by priority. */
    public void seedPinsOnce(UUID tenant, UUID actor) {
        int claimed = jdbc.sql("""
                        INSERT INTO actor_agent_preferences (tenant_id, actor_id, pins_seeded) VALUES (:tenant, :actor, TRUE)
                        ON CONFLICT (tenant_id, actor_id) DO UPDATE SET pins_seeded = TRUE
                        WHERE NOT actor_agent_preferences.pins_seeded
                        """).param("tenant", tenant).param("actor", actor).update();
        if (claimed == 0) return;
        jdbc.sql("""
                        INSERT INTO actor_pinned_persona (tenant_id, actor_id, persona_id, position)
                        SELECT :tenant, :actor, featured.id, featured.position FROM (
                            SELECT p.id, (row_number() OVER (ORDER BY p.display_priority NULLS LAST, p.id) - 1)::int AS position
                            FROM persona p WHERE p.tenant_id = :tenant AND p.deleted_at IS NULL AND p.builtin_key IS NULL
                              AND p.is_featured AND p.is_public AND p.is_listed
                            ORDER BY p.display_priority NULLS LAST, p.id LIMIT 100) featured
                        ON CONFLICT DO NOTHING
                        """).param("tenant", tenant).param("actor", actor).update();
    }

    private void markPinsSeeded(UUID tenant, UUID actor) {
        jdbc.sql("""
                        INSERT INTO actor_agent_preferences (tenant_id, actor_id, pins_seeded) VALUES (:tenant, :actor, TRUE)
                        ON CONFLICT (tenant_id, actor_id) DO UPDATE SET pins_seeded = TRUE
                        """).param("tenant", tenant).param("actor", actor).update();
    }

    private List<Object[]> rows(String sql, UUID tenant, Collection<UUID> ids) {
        return jdbc.sql(sql).param("tenant", tenant).param("ids", ids)
                .query((row, ignored) -> new Object[] {row.getObject(1, UUID.class), row.getString(2)}).list();
    }

    private Map<UUID, List<AgentRef>> refs(String sql, UUID tenant, Collection<UUID> ids) {
        var result = new LinkedHashMap<UUID, List<AgentRef>>();
        jdbc.sql(sql).param("tenant", tenant).param("ids", ids)
                .query((row, ignored) -> result.computeIfAbsent(row.getObject(1, UUID.class), key -> new ArrayList<>())
                        .add(new AgentRef(row.getObject(2, UUID.class), row.getString(3)))).list();
        return result;
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
