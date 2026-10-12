package io.memoryos.connector.lark.persistence;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkSourceService.Configuration;
import io.memoryos.connector.LarkSourceService.RootKind;
import io.memoryos.connector.LarkSourceService.RootView;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcLarkSourceRepository {
    private final JdbcClient jdbc;
    private final JdbcSourceRepository sources;

    public JdbcLarkSourceRepository(JdbcClient jdbc, JdbcSourceRepository sources) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    /** Creates the connector, the Source and its roots once verification has read every one of them. */
    public SourceId create(TenantId tenant, SourceId source, ActorId actor, @Nullable ActorId managerActor,
            String name, CredentialId credential, SourceAccess access, int syncIntervalMinutes,
            List<RootView> roots) {
        UUID connector = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO connectors (id, tenant_id, name, connector_type, status)
                VALUES (:id, :tenant, :name, 'LARK', 'ACTIVE')
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
                INSERT INTO source_sync_state (tenant_id, source_id, sync_interval_minutes)
                VALUES (:tenant, :source, :sync)
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("sync", syncIntervalMinutes).update();
        jdbc.sql("INSERT INTO lark_sources (tenant_id, source_id) VALUES (:tenant, :source)")
                .param("tenant", tenant.value()).param("source", source.value()).update();
        writeRoots(tenant, source, roots);
        return source;
    }

    /** Replaces the roots of an existing Source and starts its next run at once. */
    public void replaceScope(TenantId tenant, SourceId source, long expectedScopeRevision, int syncIntervalMinutes,
            List<RootView> roots) {
        int changed = jdbc.sql("""
                UPDATE source_sync_state SET scope_revision = scope_revision + 1, sync_interval_minutes = :sync,
                    next_sync_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND source_id = :source AND scope_revision = :expected
                """).param("sync", syncIntervalMinutes).param("tenant", tenant.value())
                .param("source", source.value()).param("expected", expectedScopeRevision).update();
        if (changed != 1) throw SourceException.conflict("Lark scope revision is stale");
        jdbc.sql("UPDATE connector_credential_pairs SET sync_error_code = NULL WHERE tenant_id = :tenant AND id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).update();
        writeRoots(tenant, source, roots);
    }

    private void writeRoots(TenantId tenant, SourceId source, List<RootView> roots) {
        jdbc.sql("DELETE FROM lark_roots WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).update();
        int position = 0;
        for (RootView root : roots) {
            jdbc.sql("""
                    INSERT INTO lark_roots (tenant_id, source_id, position, kind, token, display_name)
                    VALUES (:tenant, :source, :position, :kind, :token, :name)
                    """).param("tenant", tenant.value()).param("source", source.value()).param("position", position++)
                    .param("kind", root.kind().name()).param("token", root.token())
                    .param("name", root.displayName()).update();
        }
    }

    public ConfigurationRow configuration(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT y.scope_revision, y.schedule_revision, y.sync_interval_minutes, y.sync_paused,
                  y.last_synced_at, pair.sync_error_code AS error_code,
                  (SELECT COUNT(*) FROM lark_roots r
                    WHERE r.tenant_id = s.tenant_id AND r.source_id = s.source_id) AS root_count,
                  EXISTS (SELECT 1 FROM index_attempts a WHERE a.tenant_id = s.tenant_id
                    AND a.connector_credential_pair_id = s.source_id
                    AND a.status IN ('NOT_STARTED','IN_PROGRESS')) AS pending
                FROM lark_sources s
                JOIN source_sync_state y ON y.tenant_id = s.tenant_id AND y.source_id = s.source_id
                JOIN connector_credential_pairs pair ON pair.tenant_id = s.tenant_id AND pair.id = s.source_id
                WHERE s.tenant_id = :tenant AND s.source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new ConfigurationRow(r.getLong("root_count"), r.getInt("sync_interval_minutes"),
                        r.getLong("scope_revision"), r.getLong("schedule_revision"), r.getBoolean("sync_paused"),
                        instant(r.getTimestamp("last_synced_at")), r.getBoolean("pending"), r.getString("error_code")))
                .optional().orElseThrow(SourceException::notFound);
    }

    public List<RootView> roots(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT kind, token, display_name FROM lark_roots
                WHERE tenant_id = :tenant AND source_id = :source ORDER BY position
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new RootView(RootKind.valueOf(r.getString("kind")), r.getString("token"),
                        r.getString("display_name"))).list();
    }

    public long updateSchedule(TenantId tenant, SourceId source, long expectedScheduleRevision,
            int syncIntervalMinutes) {
        int changed = jdbc.sql("""
                UPDATE source_sync_state SET sync_interval_minutes = :sync,
                    schedule_revision = schedule_revision + 1,
                    next_sync_at = LEAST(next_sync_at, CURRENT_TIMESTAMP + make_interval(mins => :sync))
                WHERE tenant_id = :tenant AND source_id = :source AND schedule_revision = :expected
                """).param("sync", syncIntervalMinutes).param("tenant", tenant.value())
                .param("source", source.value()).param("expected", expectedScheduleRevision).update();
        if (changed != 1) throw SourceException.conflict("Lark schedule revision is stale");
        return expectedScheduleRevision + 1;
    }

    public long setPaused(TenantId tenant, SourceId source, long expectedScheduleRevision, boolean paused) {
        int changed = jdbc.sql("""
                UPDATE source_sync_state SET sync_paused = :paused, schedule_revision = schedule_revision + 1,
                    next_sync_at = CASE WHEN :paused THEN next_sync_at ELSE CURRENT_TIMESTAMP END
                WHERE tenant_id = :tenant AND source_id = :source AND schedule_revision = :expected
                """).param("paused", paused).param("tenant", tenant.value()).param("source", source.value())
                .param("expected", expectedScheduleRevision).update();
        if (changed != 1) throw SourceException.conflict("Lark schedule revision is stale");
        return expectedScheduleRevision + 1;
    }

    public void requestSynchronization(TenantId tenant, SourceId source) {
        jdbc.sql("""
                UPDATE source_sync_state SET next_sync_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    public CredentialId credentialId(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT p.credential_id FROM connector_credential_pairs p
                JOIN connectors c ON c.tenant_id = p.tenant_id AND c.id = p.connector_id
                WHERE p.tenant_id = :tenant AND p.id = :source AND c.connector_type = 'LARK'
                  AND c.status = 'ACTIVE' AND p.status <> 'DELETING'
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new CredentialId(r.getObject("credential_id", UUID.class)))
                .optional().orElseThrow(SourceException::notFound);
    }

    public void lock(TenantId tenant, SourceId source) {
        sources.lock(tenant, source);
    }

    private static @Nullable Instant instant(@Nullable Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record ConfigurationRow(long rootCount, int syncIntervalMinutes, long scopeRevision, long scheduleRevision,
                                   boolean syncPaused, @Nullable Instant lastSyncedAt, boolean pending,
                                   @Nullable String errorCode) {
        public Configuration view(SourceId source, CredentialId credential, String credentialName,
                String credentialStatus, long credentialRevision, @Nullable SourceOperationView pendingSelection) {
            return new Configuration(source, credential, credentialName, credentialStatus, credentialRevision,
                    scopeRevision, rootCount, syncIntervalMinutes, scheduleRevision, syncPaused, lastSyncedAt,
                    pending, errorCode, pendingSelection);
        }
    }
}
