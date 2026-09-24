package io.memoryos.chat.persistence;

import io.memoryos.chat.AgentPerson;
import io.memoryos.chat.AgentRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tenant-qualified Document Set rows and their explicit shares and Source/persona associations. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcDocumentSetRepository {
    public record Row(UUID id, UUID ownerActorId, long revision, String name, String description, boolean isPublic, Instant createdAt,
                      Instant updatedAt, @Nullable Instant deletedAt) {}
    public record Access(UUID id, boolean uses, boolean edits) {}
    public record Details(List<UUID> sourceIds, List<AgentPerson> userShares,
                          List<AgentRef> groupShares) {}

    private final JdbcClient jdbc;
    public JdbcDocumentSetRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<UUID> list(UUID tenant, UUID actor, boolean agentsManage, int offset, int limit) {
        return jdbc.sql("""
                        SELECT d.id FROM document_set d
                        WHERE d.tenant_id = :tenant AND d.deleted_at IS NULL AND %s
                        ORDER BY lower(d.name), d.id OFFSET :offset LIMIT :limit
                        """.formatted(DocumentSetAccessSql.USES))
                .param("tenant", tenant).param("actor", actor).param("agentsManage", agentsManage)
                .param("offset", offset).param("limit", limit).query(UUID.class).list();
    }

    public @Nullable Row locked(UUID tenant, UUID id) {
        return jdbc.sql("""
                        SELECT id, owner_actor_id, revision, name, description, is_public, created_at, updated_at, deleted_at
                        FROM document_set WHERE tenant_id = :tenant AND id = :id FOR UPDATE
                        """).param("tenant", tenant).param("id", id).query(this::row).optional().orElse(null);
    }

    public @Nullable Row read(UUID tenant, UUID id) {
        return jdbc.sql("""
                        SELECT id, owner_actor_id, revision, name, description, is_public, created_at, updated_at, deleted_at
                        FROM document_set WHERE tenant_id = :tenant AND id = :id
                        """).param("tenant", tenant).param("id", id).query(this::row).optional().orElse(null);
    }

    public Map<UUID, Access> access(UUID tenant, UUID actor, boolean agentsManage, Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var result = new HashMap<UUID, Access>();
        jdbc.sql("""
                        SELECT d.id, %s AS uses, %s AS edits FROM document_set d
                        WHERE d.tenant_id = :tenant AND d.id IN (:ids)
                        """.formatted(DocumentSetAccessSql.USES, DocumentSetAccessSql.EDITS))
                .param("tenant", tenant).param("actor", actor).param("agentsManage", agentsManage).param("ids", ids)
                .query((row, ignored) -> new Access(row.getObject("id", UUID.class), row.getBoolean("uses"), row.getBoolean("edits")))
                .list().forEach(access -> result.put(access.id(), access));
        return result;
    }

    public void insert(UUID tenant, UUID id, UUID owner, String name, String description, boolean isPublic) {
        jdbc.sql("""
                        INSERT INTO document_set (tenant_id, id, owner_actor_id, name, description, is_public)
                        VALUES (:tenant, :id, :owner, :name, :description, :isPublic)
                        """).param("tenant", tenant).param("id", id).param("owner", owner).param("name", name).param("description", description)
                .param("isPublic", isPublic).update();
    }

    public boolean update(UUID tenant, UUID id, long revision, String name, String description, boolean isPublic) {
        return jdbc.sql("""
                        UPDATE document_set SET name = :name, description = :description, is_public = :isPublic,
                          updated_at = CURRENT_TIMESTAMP, revision = revision + 1
                        WHERE tenant_id = :tenant AND id = :id AND revision = :revision AND deleted_at IS NULL
                        """).param("tenant", tenant).param("id", id).param("revision", revision).param("name", name).param("description", description)
                .param("isPublic", isPublic).update() == 1;
    }

    public boolean delete(UUID tenant, UUID id, long revision) {
        int updated = jdbc.sql("""
                        UPDATE document_set SET deleted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP, revision = revision + 1
                        WHERE tenant_id = :tenant AND id = :id AND revision = :revision AND deleted_at IS NULL
                        """).param("tenant", tenant).param("id", id).param("revision", revision).update();
        if (updated == 1) {
            jdbc.sql("DELETE FROM persona_document_set WHERE tenant_id = :tenant AND document_set_id = :id")
                    .param("tenant", tenant).param("id", id).update();
        }
        return updated == 1;
    }

    public boolean replaceShares(UUID tenant, UUID id, long revision, Collection<UUID> users, Collection<UUID> groups) {
        if (!advance(tenant, id, revision)) return false;
        jdbc.sql("DELETE FROM document_set_user_share WHERE tenant_id = :tenant AND document_set_id = :id")
                .param("tenant", tenant).param("id", id).update();
        jdbc.sql("DELETE FROM document_set_group_share WHERE tenant_id = :tenant AND document_set_id = :id")
                .param("tenant", tenant).param("id", id).update();
        users.forEach(actor -> jdbc.sql("INSERT INTO document_set_user_share (tenant_id, document_set_id, actor_id) VALUES (:tenant, :id, :actor)")
                .param("tenant", tenant).param("id", id).param("actor", actor).update());
        groups.forEach(group -> jdbc.sql("INSERT INTO document_set_group_share (tenant_id, document_set_id, group_id) VALUES (:tenant, :id, :group)")
                .param("tenant", tenant).param("id", id).param("group", group).update());
        return true;
    }

    public void replaceSources(UUID tenant, UUID id, Collection<UUID> sources) {
        jdbc.sql("DELETE FROM document_set_source WHERE tenant_id = :tenant AND document_set_id = :id")
                .param("tenant", tenant).param("id", id).update();
        sources.forEach(source -> jdbc.sql("INSERT INTO document_set_source (tenant_id, document_set_id, source_id) VALUES (:tenant, :id, :source)")
                .param("tenant", tenant).param("id", id).param("source", source).update());
    }

    public void replacePersonaSets(UUID tenant, UUID persona, Collection<UUID> sets) {
        jdbc.sql("DELETE FROM persona_document_set WHERE tenant_id = :tenant AND persona_id = :persona")
                .param("tenant", tenant).param("persona", persona).update();
        sets.forEach(set -> jdbc.sql("INSERT INTO persona_document_set (tenant_id, persona_id, document_set_id) VALUES (:tenant, :persona, :set)")
                .param("tenant", tenant).param("persona", persona).param("set", set).update());
    }

    public List<UUID> personaSets(UUID tenant, UUID persona) {
        return jdbc.sql("SELECT document_set_id FROM persona_document_set WHERE tenant_id = :tenant AND persona_id = :persona ORDER BY document_set_id")
                .param("tenant", tenant).param("persona", persona).query(UUID.class).list();
    }

    /** All requested IDs must be usable; an empty set remains a valid, narrowing selection. */
    public Set<UUID> usableSourceIds(UUID tenant, UUID actor, boolean agentsManage, Collection<UUID> sets) {
        if (sets.isEmpty()) return Set.of();
        var usable = jdbc.sql("""
                        SELECT d.id FROM document_set d WHERE d.tenant_id = :tenant AND d.deleted_at IS NULL
                          AND d.id IN (:sets) AND %s
                        """.formatted(DocumentSetAccessSql.USES))
                .param("tenant", tenant).param("actor", actor).param("agentsManage", agentsManage).param("sets", sets).query(UUID.class).list();
        if (usable.size() != sets.size()) return Set.of();
        return Set.copyOf(jdbc.sql("""
                        SELECT source_id FROM document_set_source
                        WHERE tenant_id = :tenant AND document_set_id IN (:sets)
                        """).param("tenant", tenant).param("sets", sets).query(UUID.class).list());
    }

    public Details details(UUID tenant, UUID id) {
        var sources = jdbc.sql("SELECT source_id FROM document_set_source WHERE tenant_id = :tenant AND document_set_id = :id ORDER BY source_id")
                .param("tenant", tenant).param("id", id).query(UUID.class).list();
        var users = jdbc.sql("""
                        SELECT s.actor_id, profile.display_name, profile.email FROM document_set_user_share s
                        LEFT JOIN actor_profiles profile ON profile.actor_id = s.actor_id
                        WHERE s.tenant_id = :tenant AND s.document_set_id = :id
                        ORDER BY lower(coalesce(profile.display_name, profile.email, '')), s.actor_id
                        """).param("tenant", tenant).param("id", id)
                .query((row, ignored) -> new AgentPerson(row.getObject("actor_id", UUID.class), row.getString("display_name"), row.getString("email"))).list();
        var groups = jdbc.sql("""
                        SELECT g.id, g.name FROM document_set_group_share s
                        JOIN iam_groups g ON g.tenant_id = s.tenant_id AND g.id = s.group_id
                        WHERE s.tenant_id = :tenant AND s.document_set_id = :id ORDER BY lower(g.name), g.id
                        """).param("tenant", tenant).param("id", id)
                .query((row, ignored) -> new AgentRef(row.getObject("id", UUID.class), row.getString("name"))).list();
        return new Details(sources, users, groups);
    }

    public Map<UUID, List<UUID>> personaSets(UUID tenant, Collection<UUID> personas) {
        if (personas.isEmpty()) return Map.of();
        var result = new LinkedHashMap<UUID, List<UUID>>();
        jdbc.sql("""
                        SELECT persona_id, document_set_id FROM persona_document_set
                        WHERE tenant_id = :tenant AND persona_id IN (:personas)
                        ORDER BY persona_id, document_set_id
                        """).param("tenant", tenant).param("personas", personas)
                .query((row, ignored) -> result.computeIfAbsent(row.getObject("persona_id", UUID.class), key -> new ArrayList<>())
                        .add(row.getObject("document_set_id", UUID.class))).list();
        return result;
    }

    public Map<UUID, String> names(UUID tenant, Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var result = new LinkedHashMap<UUID, String>();
        jdbc.sql("""
                        SELECT id, name FROM document_set
                        WHERE tenant_id = :tenant AND id IN (:ids) AND deleted_at IS NULL
                        """).param("tenant", tenant).param("ids", ids)
                .query((row, ignored) -> result.put(row.getObject("id", UUID.class), row.getString("name"))).list();
        return result;
    }

    private boolean advance(UUID tenant, UUID id, long revision) {
        return jdbc.sql("""
                        UPDATE document_set SET updated_at = CURRENT_TIMESTAMP, revision = revision + 1
                        WHERE tenant_id = :tenant AND id = :id AND revision = :revision AND deleted_at IS NULL
                        """).param("tenant", tenant).param("id", id).param("revision", revision).update() == 1;
    }

    private Row row(java.sql.ResultSet row, int ignored) throws java.sql.SQLException {
        var deleted = row.getTimestamp("deleted_at");
        return new Row(row.getObject("id", UUID.class), row.getObject("owner_actor_id", UUID.class), row.getLong("revision"), row.getString("name"),
                row.getString("description"), row.getBoolean("is_public"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                deleted == null ? null : deleted.toInstant());
    }
}
