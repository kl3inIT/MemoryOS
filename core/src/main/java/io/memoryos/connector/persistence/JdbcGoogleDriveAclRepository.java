package io.memoryos.connector.persistence;

import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveAclService;
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
import io.memoryos.iam.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcGoogleDriveAclRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FILES = """
            WITH file_ids AS (
                SELECT file_id FROM google_drive_membership WHERE tenant_id = :tenant AND source_id = :source
                UNION SELECT file_id FROM google_drive_roots WHERE tenant_id = :tenant AND source_id = :source
                UNION SELECT file_id FROM google_drive_link_approvals WHERE tenant_id = :tenant AND source_id = :source
                UNION SELECT file_id FROM google_drive_acl_snapshots WHERE tenant_id = :tenant AND source_id = :source
            ), files AS (
                SELECT f.file_id, COALESCE(r.name, linked.name, v.filename, f.file_id) AS name
                FROM file_ids f
                JOIN connector_credential_pairs p ON p.tenant_id = :tenant AND p.id = :source
                LEFT JOIN google_drive_roots r ON r.tenant_id = p.tenant_id AND r.source_id = p.id AND r.file_id = f.file_id
                LEFT JOIN google_drive_linked_documents linked ON linked.tenant_id = p.tenant_id
                    AND linked.source_id = p.id AND linked.file_id = f.file_id
                LEFT JOIN connector_items i ON i.tenant_id = p.tenant_id AND i.connector_id = p.connector_id
                    AND i.provider_file_id = f.file_id
                LEFT JOIN connector_item_versions v ON v.tenant_id = i.tenant_id AND v.id = i.current_version_id
            )
            """;
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
    private static final String CONTEXT_JOINS = """
            JOIN google_drive_sources s ON s.tenant_id = :tenant AND s.source_id = :source
            JOIN connector_credential_pairs p ON p.tenant_id = s.tenant_id AND p.id = s.source_id
            JOIN connectors c ON c.tenant_id = p.tenant_id AND c.id = p.connector_id
            JOIN tenants t ON t.id = p.tenant_id
            JOIN credentials credential ON credential.tenant_id = p.tenant_id AND credential.id = p.credential_id
            LEFT JOIN google_drive_credentials g ON g.tenant_id = p.tenant_id AND g.credential_id = p.credential_id
            LEFT JOIN google_drive_acl_snapshots a ON a.tenant_id = p.tenant_id AND a.source_id = p.id AND a.file_id = f.file_id
            LEFT JOIN google_drive_membership m ON m.tenant_id = p.tenant_id AND m.source_id = p.id AND m.file_id = f.file_id
            LEFT JOIN connector_items i ON i.tenant_id = p.tenant_id AND i.connector_id = p.connector_id AND i.provider_file_id = f.file_id
            """;
    private final JdbcClient jdbc;

    public JdbcGoogleDriveAclRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

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
        int changed = jdbc.sql("""
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
                """).param("tenant", work.tenantId().value()).param("source", work.sourceId().value())
                .param("file", fileId).param("permissions", permissions).param("success", permissions != null)
                .param("status", permissions == null ? Status.FAILED.name() : Status.SUCCEEDED.name())
                .param("operation", work.operationId().value()).param("credential", work.credentialRevision())
                .param("scope", work.scopeRevision()).param("generation", work.generation())
                .param("error", errorCode).param("errorMessage", errorMessage).update();
        if (changed != 1) throw SourceException.notFound();
    }

    /** One database statement observes payload, lifecycle context and source-qualified Document mapping. */
    public Optional<GoogleDriveAclSnapshot> read(TenantId tenantId, SourceId sourceId, String fileId) {
        return get(tenantId, sourceId, fileId).map(GoogleDriveAclService.File::snapshot);
    }

    public Optional<GoogleDriveAclService.File> get(TenantId tenantId, SourceId sourceId, String fileId) {
        return jdbc.sql(FILES + """
                SELECT f.file_id, f.name, a.tenant_id, a.source_id, a.observation_revision, a.permissions_json, a.status,
                    a.last_attempt_at, a.attempt_operation_id, a.attempt_credential_id, a.attempt_credential_revision,
                    a.attempt_scope_revision, a.attempt_generation, a.last_success_at, a.success_operation_id,
                    a.success_credential_id, a.success_credential_revision, a.success_scope_revision,
                    a.success_generation, a.error_code, a.error_message, i.id AS item_id, d.id AS document_id,
                    statement_timestamp() AS read_at,
                """ + CONTEXT_COLUMNS + " FROM files f " + CONTEXT_JOINS + """
                LEFT JOIN documents_by_connector_credential_pair mapping ON mapping.tenant_id = p.tenant_id
                    AND mapping.connector_credential_pair_id = p.id AND mapping.connector_item_id = i.id
                LEFT JOIN documents d ON d.tenant_id = mapping.tenant_id AND d.id = mapping.document_id
                WHERE f.file_id = :file
                """).param("tenant", tenantId.value()).param("source", sourceId.value()).param("file", fileId)
                .query((row, n) -> new GoogleDriveAclService.File(row.getString("file_id"), row.getString("name"),
                        row.getString("status") == null ? null : snapshot(row, n))).optional();
    }

    public GoogleDriveAclService.Page list(TenantId tenant, SourceId source, GoogleDriveAclService.Query query) {
        String search = query.query() == null ? "" : query.query().strip();
        String prefix = tenant.value() + "|" + source.value() + "|" + encode(search) + "|";
        String after = "";
        if (query.cursor() != null) {
            try {
                if (query.cursor().length() > 2048) throw new IllegalArgumentException();
                String decoded = new String(Base64.getUrlDecoder().decode(query.cursor()), StandardCharsets.UTF_8);
                if (!decoded.startsWith(prefix)) throw new IllegalArgumentException();
                after = decoded.substring(prefix.length());
                if (!after.matches("[A-Za-z0-9_-]{1,256}")) throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                throw SourceException.invalid("Invalid ACL cursor.", "malformed or mismatched ACL cursor");
            }
        }
        String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var items = new ArrayList<GoogleDriveAclService.Item>();
        long total = jdbc.sql(FILES + """
                , filtered AS (
                    SELECT * FROM files WHERE name ILIKE :search ESCAPE '!' OR file_id ILIKE :search ESCAPE '!'
                ), page AS (
                    SELECT * FROM filtered WHERE file_id COLLATE "C" > :after COLLATE "C"
                    ORDER BY file_id COLLATE "C" LIMIT :limit
                ), projected AS (
                    SELECT f.file_id, f.name, a.status, a.observation_revision,
                        jsonb_array_length(a.permissions_json) AS permission_count,
                        a.last_attempt_at, a.last_success_at, a.success_operation_id, a.success_credential_id,
                        a.success_credential_revision, a.success_scope_revision, a.success_generation,
                """ + CONTEXT_COLUMNS + " FROM page f " + CONTEXT_JOINS + """
                )
                SELECT projected.*, totals.total_items
                FROM (SELECT COUNT(*) AS total_items FROM filtered) totals
                LEFT JOIN projected ON TRUE ORDER BY projected.file_id COLLATE "C"
                """).param("tenant", tenant.value()).param("source", source.value()).param("search", pattern)
                .param("after", after).param("limit", query.size() + 1).query(row -> {
                    long count = 0;
                    while (row.next()) {
                        count = row.getLong("total_items");
                        if (row.getString("file_id") == null) continue;
                        String status = row.getString("status");
                        Observation success = row.getTimestamp("last_success_at") == null ? null : observation(row, "success");
                        items.add(new GoogleDriveAclService.Item(row.getString("file_id"), row.getString("name"),
                                status == null ? null : Status.valueOf(status),
                                status == null ? null : contextStatus(success, currentContext(row)),
                                row.getObject("observation_revision", Long.class), row.getObject("permission_count", Integer.class),
                                JdbcSourceRepository.instant(row, "last_success_at"), JdbcSourceRepository.instant(row, "last_attempt_at")));
                    }
                    return count;
                });
        boolean more = items.size() > query.size();
        if (more) items.removeLast();
        return new GoogleDriveAclService.Page(items, more ? encode(prefix + items.getLast().fileId()) : null, total);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
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
