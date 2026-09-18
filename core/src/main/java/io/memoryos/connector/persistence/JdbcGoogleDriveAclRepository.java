package io.memoryos.connector.persistence;

import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveAclChanged;
import io.memoryos.connector.GoogleDriveAclReader;
import io.memoryos.connector.GoogleDriveAclSnapshot;
import io.memoryos.connector.GoogleDriveAclSnapshot.ContextStatus;
import io.memoryos.connector.GoogleDriveAclSnapshot.CurrentContext;
import io.memoryos.connector.GoogleDriveAclSnapshot.Observation;
import io.memoryos.connector.GoogleDriveAclSnapshot.Status;
import io.memoryos.connector.GoogleDriveProvider.Permission;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.tenant.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcGoogleDriveAclRepository implements GoogleDriveAclReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONTEXT_COLUMNS = """
            t.status = 'ACTIVE' AS tenant_active,
            p.status <> 'DELETING' AND c.status = 'ACTIVE' AND c.connector_type = 'GOOGLE_DRIVE' AS source_active,
            p.credential_id AS current_credential_id, COALESCE(g.credential_revision, 0) AS current_credential_revision,
            credential.status = 'ACTIVE' AND g.connection_status = 'ACTIVE'
                AND g.oauth_client_ciphertext IS NOT NULL AND g.refresh_token_ciphertext IS NOT NULL AS credential_active,
            s.revision AS current_scope_revision, s.generation AS current_generation,
            m.generation AS membership_generation,
            COALESCE(NOT m.excluded AND m.root_id IS NOT NULL AND (
                EXISTS (SELECT 1 FROM google_drive_roots r WHERE r.tenant_id = p.tenant_id
                    AND r.source_id = p.id AND r.file_id = m.root_id)
                OR EXISTS (SELECT 1 FROM google_drive_link_approvals approval WHERE approval.tenant_id = p.tenant_id
                    AND approval.source_id = p.id AND approval.file_id = m.root_id)), FALSE) AS selected,
            COALESCE(i.status = 'DELETING', FALSE) AS item_removed
            """;
    private static final String CONTEXT_TAIL = """
            JOIN connectors c ON c.tenant_id = p.tenant_id AND c.id = p.connector_id
            JOIN tenants t ON t.id = p.tenant_id
            JOIN credentials credential ON credential.tenant_id = p.tenant_id AND credential.id = p.credential_id
            LEFT JOIN google_drive_credentials g ON g.tenant_id = p.tenant_id AND g.credential_id = p.credential_id
            LEFT JOIN google_drive_acl_snapshots a ON a.tenant_id = p.tenant_id AND a.source_id = p.id AND a.file_id = f.file_id
            LEFT JOIN google_drive_membership m ON m.tenant_id = p.tenant_id AND m.source_id = p.id AND m.file_id = f.file_id
            LEFT JOIN connector_items i ON i.tenant_id = p.tenant_id AND i.connector_id = p.connector_id AND i.provider_file_id = f.file_id
            """;
    /** Projects retained snapshots of the (source_id, file_id) rows of a {@code files} CTE within {@code :tenant}. */
    private static final String SNAPSHOTS = """
            SELECT f.file_id, a.tenant_id, a.source_id, a.observation_revision, a.permissions_json, a.status,
                a.last_attempt_at, a.attempt_operation_id, a.attempt_credential_id, a.attempt_credential_revision,
                a.attempt_scope_revision, a.attempt_generation, a.last_success_at, a.success_operation_id,
                a.success_credential_id, a.success_credential_revision, a.success_scope_revision,
                a.success_generation, a.error_code, a.error_message, i.id AS item_id, d.id AS document_id,
                statement_timestamp() AS read_at,
            """ + CONTEXT_COLUMNS + """
            FROM files f
            JOIN google_drive_sources s ON s.tenant_id = :tenant AND s.source_id = f.source_id
            JOIN connector_credential_pairs p ON p.tenant_id = s.tenant_id AND p.id = s.source_id
            """ + CONTEXT_TAIL + """
            LEFT JOIN documents_by_connector_credential_pair mapping ON mapping.tenant_id = p.tenant_id
                AND mapping.connector_credential_pair_id = p.id AND mapping.connector_item_id = i.id
            LEFT JOIN documents d ON d.tenant_id = mapping.tenant_id AND d.id = mapping.document_id
            WHERE a.file_id IS NOT NULL
            """;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    public JdbcGoogleDriveAclRepository(JdbcClient jdbc, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** Caller holds the source, credential, scope and operation fences in its transaction. */
    public void recordSuccess(Work work, String fileId, List<Permission> permissions) {
        record(work, fileId, JSON.writeValueAsString(List.copyOf(permissions)), null, null);
    }

    /** Retains the last complete snapshot and its provenance, independently of this failed attempt. */
    public void recordFailure(Work work, String fileId, String errorCode, @Nullable String errorMessage) {
        record(work, fileId, null, WorkLeases.safeErrorCode(errorCode), WorkLeases.safeErrorMessage(errorMessage));
    }

    private void record(Work work, String fileId, @Nullable String permissions, @Nullable String errorCode,
            @Nullable String errorMessage) {
        var row = jdbc.sql("""
                WITH prior AS (
                    SELECT permissions_json, status FROM google_drive_acl_snapshots
                    WHERE tenant_id = :tenant AND source_id = :source AND file_id = :file
                ), upserted AS (
                    INSERT INTO google_drive_acl_snapshots (tenant_id, source_id, file_id, observation_revision,
                        permissions_json, status, last_attempt_at, attempt_operation_id, attempt_credential_id,
                        attempt_credential_revision, attempt_scope_revision, attempt_generation, last_success_at,
                        success_operation_id, success_credential_id, success_credential_revision,
                        success_scope_revision, success_generation, error_code, error_message)
                    SELECT p.tenant_id, p.id, :file, CASE WHEN :success THEN 1 ELSE 0 END,
                        CAST(:permissions AS jsonb), :status, statement_timestamp(), :operation, p.credential_id,
                        :credential, :scope, :generation, CASE WHEN :success THEN statement_timestamp() END,
                        CASE WHEN :success THEN CAST(:operation AS uuid) END,
                        CASE WHEN :success THEN p.credential_id END,
                        CASE WHEN :success THEN CAST(:credential AS bigint) END,
                        CASE WHEN :success THEN CAST(:scope AS bigint) END,
                        CASE WHEN :success THEN CAST(:generation AS bigint) END, :error, :errorMessage
                    FROM connector_credential_pairs p WHERE p.tenant_id = :tenant AND p.id = :source
                    ON CONFLICT (tenant_id, source_id, file_id) DO UPDATE SET
                        observation_revision = google_drive_acl_snapshots.observation_revision + EXCLUDED.observation_revision,
                        permissions_json = COALESCE(EXCLUDED.permissions_json, google_drive_acl_snapshots.permissions_json),
                        status = EXCLUDED.status, last_attempt_at = EXCLUDED.last_attempt_at,
                        attempt_operation_id = EXCLUDED.attempt_operation_id,
                        attempt_credential_id = EXCLUDED.attempt_credential_id,
                        attempt_credential_revision = EXCLUDED.attempt_credential_revision,
                        attempt_scope_revision = EXCLUDED.attempt_scope_revision, attempt_generation = EXCLUDED.attempt_generation,
                        last_success_at = COALESCE(EXCLUDED.last_success_at, google_drive_acl_snapshots.last_success_at),
                        success_operation_id = COALESCE(EXCLUDED.success_operation_id, google_drive_acl_snapshots.success_operation_id),
                        success_credential_id = COALESCE(EXCLUDED.success_credential_id, google_drive_acl_snapshots.success_credential_id),
                        success_credential_revision = COALESCE(EXCLUDED.success_credential_revision, google_drive_acl_snapshots.success_credential_revision),
                        success_scope_revision = COALESCE(EXCLUDED.success_scope_revision, google_drive_acl_snapshots.success_scope_revision),
                        success_generation = COALESCE(EXCLUDED.success_generation, google_drive_acl_snapshots.success_generation),
                        error_code = EXCLUDED.error_code, error_message = EXCLUDED.error_message
                    RETURNING observation_revision, permissions_json, status, error_code
                )
                SELECT u.observation_revision, u.status, u.error_code, u.permissions_json,
                    (SELECT prior.permissions_json FROM prior) AS prior_permissions,
                    (SELECT prior.status FROM prior) AS prior_status
                FROM upserted u
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("file", fileId).param("permissions", permissions).param("success", permissions != null)
                .param("status", permissions == null ? Status.FAILED.name() : Status.SUCCEEDED.name())
                .param("operation", work.operationId().value()).param("credential", work.credentialRevision())
                .param("scope", work.scopeRevision()).param("generation", work.generation())
                .param("error", errorCode).param("errorMessage", errorMessage)
                .query((r, n) -> new Object[] {
                        r.getLong("observation_revision"), r.getString("status"), r.getString("error_code"),
                        r.getString("permissions_json"), r.getString("prior_permissions"),
                        r.getString("prior_status") }).optional();
        if (row.isEmpty()) throw SourceException.notFound();
        Object[] result = row.get();
        boolean payloadChanged = !Objects.equals(result[3], result[4]);
        boolean statusChanged = !Objects.equals(result[1], result[5]);
        if (payloadChanged || statusChanged) {
            List<DocumentId> documentIds = jdbc.sql("""
                    SELECT DISTINCT mapping.document_id FROM documents_by_connector_credential_pair mapping
                    JOIN connector_items i ON i.tenant_id = mapping.tenant_id AND i.id = mapping.connector_item_id
                    WHERE mapping.tenant_id = :tenant AND mapping.connector_credential_pair_id = :source
                        AND i.provider_file_id = :file
                    """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                    .param("file", fileId).query((r, n) -> new DocumentId(r.getObject(1, UUID.class))).list();
            events.publishEvent(new GoogleDriveAclChanged(work.tenantId(), work.sourceId(), fileId,
                    documentIds, (Long) result[0], Status.valueOf((String) result[1]), (String) result[2]));
        }
    }

    /** One database statement observes payload, lifecycle context and source-qualified Document mapping. */
    public Optional<GoogleDriveAclSnapshot> read(TenantId tenantId, SourceId sourceId, String fileId) {
        return jdbc.sql("WITH files AS (SELECT CAST(:source AS uuid) AS source_id, CAST(:file AS text) AS file_id)\n"
                        + SNAPSHOTS)
                .param("tenant", tenantId.value()).param("source", sourceId.value()).param("file", fileId)
                .query(this::snapshot).optional();
    }

    /** One statement resolves the Document's Drive mappings and returns their retained snapshots. */
    @Override
    public List<GoogleDriveAclSnapshot> readByDocument(TenantId tenantId, DocumentId documentId) {
        return jdbc.sql("""
                WITH files AS (
                    SELECT p.id AS source_id, i.provider_file_id AS file_id
                    FROM documents_by_connector_credential_pair mapping
                    JOIN connector_credential_pairs p ON p.tenant_id = mapping.tenant_id
                        AND p.id = mapping.connector_credential_pair_id
                    JOIN connector_items i ON i.tenant_id = p.tenant_id AND i.id = mapping.connector_item_id
                    WHERE mapping.tenant_id = :tenant AND mapping.document_id = :document
                )
                """ + SNAPSHOTS + "ORDER BY a.source_id, a.file_id")
                .param("tenant", tenantId.value()).param("document", documentId.value())
                .query(this::snapshot).list();
    }

    private GoogleDriveAclSnapshot snapshot(ResultSet row, int ignored) throws SQLException {
        var current = currentContext(row);
        Observation success = row.getTimestamp("last_success_at") == null ? null : observation(row, "success");
        String permissions = row.getString("permissions_json");
        UUID item = row.getObject("item_id", UUID.class);
        UUID document = row.getObject("document_id", UUID.class);
        return new GoogleDriveAclSnapshot(new TenantId(row.getObject("tenant_id", UUID.class)),
                new SourceId(row.getObject("source_id", UUID.class)), row.getString("file_id"), row.getLong("observation_revision"),
                permissions == null ? List.of() : List.of(JSON.readValue(permissions, Permission[].class)),
                Status.valueOf(row.getString("status")), observation(row, "attempt"), success, row.getString("error_code"),
                row.getString("error_message"),
                contextStatus(success, current), current, item == null ? null : new SourceItemId(item),
                document == null ? List.of() : List.of(new DocumentId(document)), row.getTimestamp("read_at").toInstant());
    }

    private static CurrentContext currentContext(ResultSet row) throws SQLException {
        return new CurrentContext(row.getBoolean("tenant_active"), row.getBoolean("source_active"),
                new CredentialId(row.getObject("current_credential_id", UUID.class)), row.getLong("current_credential_revision"),
                row.getBoolean("credential_active"), row.getLong("current_scope_revision"), row.getLong("current_generation"),
                row.getObject("membership_generation", Long.class), row.getBoolean("selected"), row.getBoolean("item_removed"));
    }

    private static Observation observation(ResultSet row, String prefix) throws SQLException {
        return new Observation(row.getTimestamp("last_" + prefix + "_at").toInstant(),
                new SourceOperationId(row.getObject(prefix + "_operation_id", UUID.class)),
                new CredentialId(row.getObject(prefix + "_credential_id", UUID.class)),
                row.getLong(prefix + "_credential_revision"), row.getLong(prefix + "_scope_revision"), row.getLong(prefix + "_generation"));
    }

    private static ContextStatus contextStatus(@Nullable Observation success, CurrentContext current) {
        if (success == null) return ContextStatus.UNOBSERVED;
        if (!current.tenantActive() || !current.sourceActive() || !current.credentialActive()
                || !current.selected() || current.itemRemoved() || !success.credentialId().equals(current.credentialId())
                || success.credentialRevision() != current.credentialRevision() || success.scopeRevision() != current.scopeRevision()) {
            return ContextStatus.INVALID;
        }
        if (success.generation() != current.generation() || current.membershipGeneration() == null
                || current.membershipGeneration().longValue() != current.generation()) return ContextStatus.STALE;
        return ContextStatus.CURRENT;
    }
}
