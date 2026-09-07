package io.memoryos.connector.persistence;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveSourceService.Root;
import io.memoryos.connector.GoogleDriveSourceService.ScopeMode;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGoogleDriveSourceRepository {
    private final JdbcClient jdbc;

    public JdbcGoogleDriveSourceRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void requireGoogle(TenantId tenant, SourceId source) {
        if (jdbc.sql("""
                SELECT COUNT(*) FROM connector_credential_pairs p
                JOIN connectors c ON c.tenant_id = p.tenant_id AND c.id = p.connector_id
                WHERE p.tenant_id = :tenant AND p.id = :source AND c.connector_type = 'GOOGLE_DRIVE'
                AND p.status <> 'DELETING'
                """).param("tenant", tenant.value()).param("source", source.value())
                .query(Integer.class).single() != 1) throw SourceException.notFound();
    }

    public SourceId create(TenantId tenant, String name, CredentialId credential, ScopeMode scopeMode, List<Root> roots) {
        UUID connector = UUID.randomUUID();
        SourceId source = new SourceId(UUID.randomUUID());
        jdbc.sql("""
                INSERT INTO connectors (id, tenant_id, name, connector_type, status)
                VALUES (:id, :tenant, :name, 'GOOGLE_DRIVE', 'ACTIVE')
                """).param("id", connector).param("tenant", tenant.value()).param("name", name).update();
        jdbc.sql("""
                INSERT INTO connector_credential_pairs (id, tenant_id, connector_id, credential_id, access_type, status)
                VALUES (:id, :tenant, :connector, :credential, 'RESTRICTED', 'NOT_STARTED')
                """).param("id", source.value()).param("tenant", tenant.value())
                .param("connector", connector).param("credential", credential.value()).update();
        initialize(tenant, source, scopeMode);
        insertRoots(tenant, source, roots);
        return source;
    }

    private void initialize(TenantId tenant, SourceId source, ScopeMode scopeMode) {
        jdbc.sql("""
                INSERT INTO google_drive_sources (tenant_id, source_id, scope_mode) VALUES (:tenant, :source, :scopeMode)
                ON CONFLICT DO NOTHING
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("scopeMode", scopeMode.name()).update();
    }

    public ScopeMode scopeMode(TenantId tenant, SourceId source) {
        return jdbc.sql("SELECT scope_mode FROM google_drive_sources WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value())
                .query(String.class).optional().map(ScopeMode::valueOf).orElseThrow(SourceException::notFound);
    }

    public ConfigurationRow configuration(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT s.*, EXISTS (SELECT 1 FROM source_sync_attempts a
                  WHERE a.tenant_id = s.tenant_id AND a.source_id = s.source_id
                  AND a.status IN ('NOT_STARTED','IN_PROGRESS')) OR EXISTS (
                  SELECT 1 FROM index_attempts a
                  JOIN connector_item_versions v ON v.tenant_id = a.tenant_id AND v.id = a.connector_item_version_id
                  JOIN google_drive_membership m ON m.tenant_id = s.tenant_id AND m.source_id = s.source_id
                    AND m.file_id = v.provider_file_id
                  WHERE a.tenant_id = s.tenant_id AND a.connector_credential_pair_id = s.source_id
                    AND a.status IN ('NOT_STARTED','IN_PROGRESS') AND v.scope_revision = s.revision
                    AND m.eligible AND NOT m.excluded) AS pending
                FROM google_drive_sources s WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new ConfigurationRow(r.getLong("revision"), r.getInt("sync_interval_minutes"),
                        r.getLong("schedule_revision"), ScopeMode.valueOf(r.getString("scope_mode")),
                        JdbcSourceRepository.instant(r, "last_synced_at"), r.getBoolean("pending"),
                        r.getString("error_code"))).optional()
                .orElseThrow(SourceException::notFound);
    }

    public List<Root> roots(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT file_id, name, mime_type FROM google_drive_roots
                WHERE tenant_id = :tenant AND source_id = :source ORDER BY file_id
                """).param("tenant", tenant.value()).param("source", source.value())
                .query((r, _) -> new Root(r.getString("file_id"), r.getString("name"), r.getString("mime_type"))).list();
    }

    public void replace(TenantId tenant, SourceId source, long expected, ScopeMode scopeMode, List<Root> roots) {
        initialize(tenant, source, scopeMode);
        if (jdbc.sql("""
                UPDATE google_drive_sources SET revision = revision + 1,
                  scope_mode = :scopeMode, error_code = NULL, next_sync_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND source_id = :source AND revision = :revision
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("scopeMode", scopeMode.name()).param("revision", expected).update() != 1) throw SourceException.staleConfiguration();
        jdbc.sql("DELETE FROM google_drive_roots WHERE tenant_id = :tenant AND source_id = :source")
                .param("tenant", tenant.value()).param("source", source.value()).update();
        insertRoots(tenant, source, roots);
        jdbc.sql("""
                UPDATE google_drive_membership SET eligible = FALSE
                WHERE tenant_id = :tenant AND source_id = :source
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    public void updateSchedule(TenantId tenant, SourceId source, long expectedRevision, int syncIntervalMinutes) {
        if (jdbc.sql("""
                UPDATE google_drive_sources SET sync_interval_minutes = :minutes,
                  schedule_revision = schedule_revision + 1,
                  next_sync_at = statement_timestamp() + :minutes * INTERVAL '1 minute'
                WHERE tenant_id = :tenant AND source_id = :source AND schedule_revision = :revision
                """).param("minutes", syncIntervalMinutes).param("tenant", tenant.value())
                .param("source", source.value()).param("revision", expectedRevision).update() != 1) {
            throw SourceException.staleConfiguration();
        }
    }

    private void insertRoots(TenantId tenant, SourceId source, List<Root> roots) {
        for (Root root : roots) {
            jdbc.sql("""
                    INSERT INTO google_drive_roots (tenant_id, source_id, file_id, name, mime_type)
                    VALUES (:tenant, :source, :file, :name, :mime)
                    """).param("tenant", tenant.value()).param("source", source.value())
                    .param("file", root.id()).param("name", root.name()).param("mime", root.mimeType()).update();
        }
    }

    public record ConfigurationRow(long revision, int syncIntervalMinutes, long scheduleRevision, ScopeMode scopeMode,
                                   @Nullable Instant lastSyncedAt, boolean pending,
                                   @Nullable String errorCode) {}
}
