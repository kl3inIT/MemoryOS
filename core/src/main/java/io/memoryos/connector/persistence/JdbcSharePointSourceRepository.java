package io.memoryos.connector.persistence;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointSourceService.Configuration;
import io.memoryos.connector.SharePointSourceService.RootKind;
import io.memoryos.connector.SharePointSourceService.RootPage;
import io.memoryos.connector.SharePointSourceService.RootView;
import io.memoryos.connector.SharePointSourceService.Scope;
import io.memoryos.connector.SharePointSourceService.ScopeMode;
import io.memoryos.connector.SharePointUrl;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSharePointSourceRepository {
    private static final int MAX_PAGE = 200;

    private final JdbcClient jdbc;
    private final JdbcSourceRepository sources;

    public JdbcSharePointSourceRepository(JdbcClient jdbc, JdbcSourceRepository sources) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    /** Creates the connector, the Source and its scope once verification has resolved every root. */
    public SourceId create(TenantId tenant, SourceId source, ActorId actor, @Nullable ActorId managerActor,
            String name, CredentialId credential, SourceAccess access, Scope scope, List<ResolvedRoot> roots,
            @Nullable String tenantHost) {
        UUID connector = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO connectors (id, tenant_id, name, connector_type, status)
                VALUES (:id, :tenant, :name, 'SHAREPOINT', 'ACTIVE')
                """).param("id", connector).param("tenant", tenant.value()).param("name", name).update();
        jdbc.sql("""
                INSERT INTO connector_credential_pairs (id, tenant_id, connector_id, credential_id, access_type,
                    status, created_by_actor_id, manager_actor_id)
                VALUES (:id, :tenant, :connector, :credential, :access, 'NOT_STARTED', :actor, :manager)
                """).param("id", source.value()).param("tenant", tenant.value()).param("connector", connector)
                .param("credential", credential.value()).param("access", access.name())
                .param("actor", actor.value()).param("manager", managerActor == null ? null : managerActor.value())
                .update();
        jdbc.sql("""
                INSERT INTO sharepoint_sources (tenant_id, source_id, scope_mode, include_documents, include_pages,
                    sync_interval_minutes, prune_interval_hours, tenant_host, next_prune_at)
                VALUES (:tenant, :source, :mode, :documents, :pages, :sync, :prune, :host,
                    -- The first run is a refresh; a prune only makes sense once a window has been collected.
                    CURRENT_TIMESTAMP + GREATEST(:prune, 1) * INTERVAL '1 hour')
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("mode", scope.scopeMode().name()).param("documents", scope.includeDocuments())
                .param("pages", scope.includePages()).param("sync", scope.syncIntervalMinutes())
                .param("prune", scope.pruneIntervalHours()).param("host", tenantHost).update();
        writeScope(tenant, source, scope, roots);
        return source;
    }

    /** Replaces the scope of an existing Source, keeping roots that are unchanged so they are not re-read. */
    public void replaceScope(TenantId tenant, SourceId source, long expectedScopeRevision, Scope scope,
            List<ResolvedRoot> roots, @Nullable String tenantHost) {
        int changed = jdbc.sql("""
                UPDATE sharepoint_sources SET scope_mode = :mode, include_documents = :documents,
                    include_pages = :pages, sync_interval_minutes = :sync, prune_interval_hours = :prune,
                    tenant_host = COALESCE(:host, tenant_host), scope_revision = scope_revision + 1,
                    error_code = NULL, next_sync_at = CURRENT_TIMESTAMP,
                    -- Activation hides every document of the old scope, so the next refresh reads the whole new
                    -- scope instead of continuing the previous change window.
                    refresh_window_end = NULL
                WHERE tenant_id = :tenant AND source_id = :source AND scope_revision = :expected
                """).param("mode", scope.scopeMode().name()).param("documents", scope.includeDocuments())
                .param("pages", scope.includePages()).param("sync", scope.syncIntervalMinutes())
                .param("prune", scope.pruneIntervalHours()).param("host", tenantHost)
                .param("tenant", tenant.value()).param("source", source.value())
                .param("expected", expectedScopeRevision).update();
        if (changed != 1) throw SourceException.conflict("SharePoint scope revision is stale");
        writeScope(tenant, source, scope, roots);
    }

    private void writeScope(TenantId tenant, SourceId source, Scope scope, List<ResolvedRoot> roots) {
        var kept = jdbc.sql("SELECT url FROM sharepoint_roots WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).query(String.class).set();
        jdbc.sql("DELETE FROM sharepoint_roots WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).update();
        int position = 0;
        for (ResolvedRoot root : roots) {
            jdbc.sql("""
                    INSERT INTO sharepoint_roots (tenant_id, source_id, position, kind, url, site_id, drive_id,
                        item_id, display_name, full_refresh_pending)
                    VALUES (:tenant, :source, :position, :kind, :url, :site, :drive, :item, :name, :pending)
                    """).param("tenant", tenant.value()).param("source", source.value()).param("position", position++)
                    .param("kind", root.kind().name()).param("url", root.url()).param("site", root.siteId())
                    .param("drive", root.driveId()).param("item", root.itemId()).param("name", root.displayName())
                    // Informational: a scope change restarts the whole Source from the epoch (refresh_window_end).
                    .param("pending", !kept.contains(root.url())).update();
        }
        jdbc.sql("DELETE FROM sharepoint_exclusions WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).update();
        insertExclusions(tenant, source, "SITE", scope.excludedSites());
        insertExclusions(tenant, source, "PATH", scope.excludedPaths());
    }

    private void insertExclusions(TenantId tenant, SourceId source, String kind, List<String> patterns) {
        int position = 0;
        for (String pattern : patterns) {
            jdbc.sql("""
                    INSERT INTO sharepoint_exclusions (tenant_id, source_id, kind, position, pattern)
                    VALUES (:tenant, :source, :kind, :position, :pattern)
                    """).param("tenant", tenant.value()).param("source", source.value()).param("kind", kind)
                    .param("position", position++).param("pattern", pattern).update();
        }
    }

    public ConfigurationRow configuration(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT s.*, pair.access_type,
                  (SELECT COUNT(*) FROM sharepoint_roots r
                    WHERE r.tenant_id = s.tenant_id AND r.source_id = s.source_id) AS root_count,
                  EXISTS (SELECT 1 FROM index_attempts a WHERE a.tenant_id = s.tenant_id
                    AND a.connector_credential_pair_id = s.source_id
                    AND a.status IN ('NOT_STARTED','IN_PROGRESS')) AS pending
                FROM sharepoint_sources s
                JOIN connector_credential_pairs pair ON pair.tenant_id = s.tenant_id AND pair.id = s.source_id
                WHERE s.tenant_id = :tenant AND s.source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new ConfigurationRow(
                        ScopeMode.valueOf(r.getString("scope_mode")), r.getLong("root_count"),
                        r.getBoolean("include_documents"), r.getBoolean("include_pages"),
                        r.getInt("sync_interval_minutes"), r.getInt("prune_interval_hours"),
                        r.getLong("scope_revision"), r.getLong("schedule_revision"), r.getBoolean("sync_paused"),
                        r.getString("tenant_host"), instant(r.getTimestamp("last_synced_at")),
                        instant(r.getTimestamp("last_pruned_at")), instant(r.getTimestamp("refresh_window_end")),
                        r.getBoolean("pending"), r.getString("error_code"),
                        SourceAccess.valueOf(r.getString("access_type"))))
                .optional().orElseThrow(SourceException::notFound);
    }

    public List<String> exclusions(TenantId tenant, SourceId source, String kind) {
        return jdbc.sql("""
                SELECT pattern FROM sharepoint_exclusions
                WHERE tenant_id = :tenant AND source_id = :source AND kind = :kind ORDER BY position
                """).param("tenant", tenant.value()).param("source", source.value()).param("kind", kind)
                .query(String.class).list();
    }

    public RootPage roots(TenantId tenant, SourceId source, @Nullable String cursor, int size) {
        int limit = Math.clamp(size, 1, MAX_PAGE);
        int after = cursor == null ? -1 : position(cursor);
        var rows = jdbc.sql("""
                SELECT position, kind, url, display_name, site_id FROM sharepoint_roots
                WHERE tenant_id = :tenant AND source_id = :source AND position > :after
                ORDER BY position LIMIT :limit
                """).param("tenant", tenant.value()).param("source", source.value()).param("after", after)
                .param("limit", limit + 1)
                .query((r, _) -> new Positioned(r.getInt("position"), new RootView(r.getString("url"),
                        RootKind.valueOf(r.getString("kind")), r.getString("display_name"),
                        r.getString("site_id") != null))).list();
        var page = new ArrayList<RootView>(Math.min(rows.size(), limit));
        for (int index = 0; index < Math.min(rows.size(), limit); index++) page.add(rows.get(index).view());
        String next = rows.size() > limit ? String.valueOf(rows.get(limit - 1).position()) : null;
        var configuration = configuration(tenant, source);
        return new RootPage(configuration.scopeRevision(), page, next, configuration.rootCount());
    }

    public List<ResolvedRoot> resolvedRoots(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT kind, url, site_id, drive_id, item_id, display_name FROM sharepoint_roots
                WHERE tenant_id = :tenant AND source_id = :source ORDER BY position
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new ResolvedRoot(RootKind.valueOf(r.getString("kind")), r.getString("url"),
                        r.getString("site_id"), r.getString("drive_id"), r.getString("item_id"),
                        r.getString("display_name"))).list();
    }

    public long updateSchedule(TenantId tenant, SourceId source, long expectedScheduleRevision,
            int syncIntervalMinutes, int pruneIntervalHours) {
        int changed = jdbc.sql("""
                UPDATE sharepoint_sources SET sync_interval_minutes = :sync, prune_interval_hours = :prune,
                    schedule_revision = schedule_revision + 1,
                    next_sync_at = LEAST(next_sync_at, CURRENT_TIMESTAMP + make_interval(mins => :sync)),
                    next_prune_at = CASE WHEN :prune = 0 THEN next_prune_at
                        ELSE LEAST(next_prune_at, CURRENT_TIMESTAMP + make_interval(hours => :prune)) END
                WHERE tenant_id = :tenant AND source_id = :source AND schedule_revision = :expected
                """).param("sync", syncIntervalMinutes).param("prune", pruneIntervalHours)
                .param("tenant", tenant.value()).param("source", source.value())
                .param("expected", expectedScheduleRevision).update();
        if (changed != 1) throw SourceException.conflict("SharePoint schedule revision is stale");
        return expectedScheduleRevision + 1;
    }

    public long setPaused(TenantId tenant, SourceId source, long expectedScheduleRevision, boolean paused) {
        int changed = jdbc.sql("""
                UPDATE sharepoint_sources SET sync_paused = :paused, schedule_revision = schedule_revision + 1,
                    next_sync_at = CASE WHEN :paused THEN next_sync_at ELSE CURRENT_TIMESTAMP END
                WHERE tenant_id = :tenant AND source_id = :source AND schedule_revision = :expected
                """).param("paused", paused).param("tenant", tenant.value()).param("source", source.value())
                .param("expected", expectedScheduleRevision).update();
        if (changed != 1) throw SourceException.conflict("SharePoint schedule revision is stale");
        return expectedScheduleRevision + 1;
    }

    public void requestSynchronization(TenantId tenant, SourceId source) {
        jdbc.sql("""
                UPDATE sharepoint_sources SET next_sync_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    public CredentialId credentialId(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT p.credential_id FROM connector_credential_pairs p
                JOIN connectors c ON c.tenant_id = p.tenant_id AND c.id = p.connector_id
                WHERE p.tenant_id = :tenant AND p.id = :source AND c.connector_type = 'SHAREPOINT'
                  AND c.status = 'ACTIVE' AND p.status <> 'DELETING'
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new CredentialId(r.getObject("credential_id", UUID.class)))
                .optional().orElseThrow(SourceException::notFound);
    }

    public void lock(TenantId tenant, SourceId source) {
        sources.lock(tenant, source);
    }

    private static int position(String cursor) {
        try {
            int value = Integer.parseInt(cursor);
            if (value < 0) throw new NumberFormatException(cursor);
            return value;
        } catch (NumberFormatException exception) {
            throw SourceException.invalid("That page cursor is not valid.", "invalid SharePoint root cursor");
        }
    }

    private static @Nullable Instant instant(@Nullable Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record Positioned(int position, RootView view) {}

    /** A root with the Graph identifiers verification resolved for it. */
    public record ResolvedRoot(RootKind kind, String url, @Nullable String siteId, @Nullable String driveId,
                               @Nullable String itemId, @Nullable String displayName) {
        public static ResolvedRoot unresolved(SharePointUrl url) {
            return new ResolvedRoot(RootKind.valueOf(url.kind().name()), url.canonical(), null, null, null, null);
        }
    }

    public record ConfigurationRow(ScopeMode scopeMode, long rootCount, boolean includeDocuments, boolean includePages,
                                   int syncIntervalMinutes, int pruneIntervalHours, long scopeRevision,
                                   long scheduleRevision, boolean syncPaused, @Nullable String tenantHost,
                                   @Nullable Instant lastSyncedAt, @Nullable Instant lastPrunedAt,
                                   @Nullable Instant refreshWindowEnd, boolean pending, @Nullable String errorCode,
                                   SourceAccess access) {
        public Configuration view(SourceId source, CredentialId credential, String credentialName,
                String credentialStatus, long credentialRevision, List<String> excludedSites,
                List<String> excludedPaths, @Nullable SourceOperationView pendingSelection) {
            return new Configuration(source, credential, credentialName, credentialStatus, credentialRevision,
                    scopeRevision, scopeMode, rootCount, excludedSites, excludedPaths, includeDocuments, includePages,
                    syncIntervalMinutes, pruneIntervalHours, scheduleRevision, syncPaused, tenantHost, lastSyncedAt,
                    lastPrunedAt, pending, errorCode, pendingSelection);
        }
    }
}
