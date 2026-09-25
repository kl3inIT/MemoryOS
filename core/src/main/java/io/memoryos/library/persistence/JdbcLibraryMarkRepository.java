package io.memoryos.library.persistence;

import io.memoryos.library.LibraryEntry;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One person's marks on what their library shows (V131): stars on reachable rows and the time they last opened any
 * row. A mark names an item and never grants it; the library resolves every marked id through its owner first.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcLibraryMarkRepository {
    private final JdbcClient jdbc;

    public JdbcLibraryMarkRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Mark(LibraryEntry.Kind kind, UUID itemId, @Nullable Instant starredAt, @Nullable Instant openedAt) {}

    /** Serializes one person's mark writes, so the star bound and the kept opens hold under concurrent requests. */
    public void lock(TenantId tenant, ActorId actor) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "library-mark:" + tenant.value() + ":" + actor.value()).query(rs -> { rs.next(); return true; });
    }

    /** The person's most recent opens, newest first. */
    public List<Mark> opened(TenantId tenant, ActorId actor, int limit) {
        return jdbc.sql("""
                SELECT kind, item_id, starred_at, opened_at FROM library_mark
                WHERE tenant_id = :tenant AND actor_id = :actor AND opened_at IS NOT NULL
                ORDER BY opened_at DESC, kind, item_id LIMIT :limit
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("limit", limit)
                .query((row, ignored) -> mark(row)).list();
    }

    /** The person's stars on reachable rows, most recently starred first. */
    public List<Mark> starred(TenantId tenant, ActorId actor, int limit) {
        return jdbc.sql("""
                SELECT kind, item_id, starred_at, opened_at FROM library_mark
                WHERE tenant_id = :tenant AND actor_id = :actor AND starred_at IS NOT NULL
                ORDER BY starred_at DESC, kind, item_id LIMIT :limit
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("limit", limit)
                .query((row, ignored) -> mark(row)).list();
    }

    /** The person's marks on {@code items}, of any kind; the caller matches the kind. */
    public List<Mark> marks(TenantId tenant, ActorId actor, Collection<UUID> items) {
        if (items.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT kind, item_id, starred_at, opened_at FROM library_mark
                WHERE tenant_id = :tenant AND actor_id = :actor AND item_id IN (:items)
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("items", items)
                .query((row, ignored) -> mark(row)).list();
    }

    public boolean isStarred(TenantId tenant, ActorId actor, LibraryEntry.Kind kind, UUID item) {
        return jdbc.sql("""
                SELECT count(*) FROM library_mark
                WHERE tenant_id = :tenant AND actor_id = :actor AND kind = :kind AND item_id = :item
                  AND starred_at IS NOT NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("kind", kind.name())
                .param("item", item).query(Long.class).single() > 0;
    }

    public long starredCount(TenantId tenant, ActorId actor) {
        return jdbc.sql("""
                SELECT count(*) FROM library_mark
                WHERE tenant_id = :tenant AND actor_id = :actor AND starred_at IS NOT NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).query(Long.class).single();
    }

    /** Stars a reachable row; starring twice keeps the first time. */
    public void star(TenantId tenant, ActorId actor, LibraryEntry.Kind kind, UUID item) {
        jdbc.sql("""
                INSERT INTO library_mark(tenant_id, actor_id, kind, item_id, starred_at)
                VALUES (:tenant, :actor, :kind, :item, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, actor_id, kind, item_id)
                DO UPDATE SET starred_at = COALESCE(library_mark.starred_at, EXCLUDED.starred_at)
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("kind", kind.name())
                .param("item", item).update();
    }

    /** Takes the stars off {@code items}; a row that marks nothing else is deleted rather than kept empty. */
    public void unstar(TenantId tenant, ActorId actor, LibraryEntry.Kind kind, Collection<UUID> items) {
        if (items.isEmpty()) return;
        jdbc.sql("""
                DELETE FROM library_mark
                WHERE tenant_id = :tenant AND actor_id = :actor AND kind = :kind AND item_id IN (:items)
                  AND opened_at IS NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("kind", kind.name())
                .param("items", items).update();
        jdbc.sql("""
                UPDATE library_mark SET starred_at = NULL
                WHERE tenant_id = :tenant AND actor_id = :actor AND kind = :kind AND item_id IN (:items)
                  AND starred_at IS NOT NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("kind", kind.name())
                .param("items", items).update();
    }

    /**
     * Records that the person opened a row now, then forgets every open but the newest {@code keep}: a row kept
     * only for an older open is deleted, and a starred one keeps its star.
     */
    public void open(TenantId tenant, ActorId actor, LibraryEntry.Kind kind, UUID item, int keep) {
        jdbc.sql("""
                INSERT INTO library_mark(tenant_id, actor_id, kind, item_id, opened_at)
                VALUES (:tenant, :actor, :kind, :item, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, actor_id, kind, item_id) DO UPDATE SET opened_at = EXCLUDED.opened_at
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("kind", kind.name())
                .param("item", item).update();
        jdbc.sql("""
                WITH kept AS (
                    SELECT kind, item_id FROM library_mark
                    WHERE tenant_id = :tenant AND actor_id = :actor AND opened_at IS NOT NULL
                    ORDER BY opened_at DESC, kind, item_id LIMIT :keep
                ), forgotten AS (
                    DELETE FROM library_mark m
                    WHERE m.tenant_id = :tenant AND m.actor_id = :actor AND m.opened_at IS NOT NULL
                      AND m.starred_at IS NULL
                      AND NOT EXISTS (SELECT 1 FROM kept WHERE kept.kind = m.kind AND kept.item_id = m.item_id)
                )
                UPDATE library_mark m SET opened_at = NULL
                WHERE m.tenant_id = :tenant AND m.actor_id = :actor AND m.opened_at IS NOT NULL
                  AND m.starred_at IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM kept WHERE kept.kind = m.kind AND kept.item_id = m.item_id)
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("keep", keep).update();
    }

    private static Mark mark(ResultSet row) throws SQLException {
        return new Mark(LibraryEntry.Kind.valueOf(row.getString("kind")), row.getObject("item_id", UUID.class),
                instant(row.getTimestamp("starred_at")), instant(row.getTimestamp("opened_at")));
    }

    private static @Nullable Instant instant(@Nullable Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
