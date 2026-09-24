package io.memoryos.connector.persistence;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SourceSelectionProcessor.Work;
import io.memoryos.connector.SharePointSourceService.Scope;
import io.memoryos.connector.SharePointSourceService.ScopeMode;
import io.memoryos.connector.SharePointSourceService.SelectionPolicy;
import io.memoryos.connector.SharePointSourceService.SelectionReceipt;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.iam.group.GroupId;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The accepted scope request, its verification progress and its outcome. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSharePointSelectionRepository {
    public static final String ROOT = "ROOT";
    public static final String EXCLUDED_SITE = "EXCLUDED_SITE";
    public static final String EXCLUDED_PATH = "EXCLUDED_PATH";

    private final JdbcClient jdbc;

    public JdbcSharePointSelectionRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Optional<SourceOperationView> find(TenantId tenant, SourceOperationId operation) {
        return jdbc.sql("SELECT * FROM sharepoint_selection_operations WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.value()).param("id", operation.value()).query(this::operation).optional();
    }

    /** Recovers the receipt of a request that was already accepted, so a retry never starts a second one. */
    public Optional<SelectionReceipt> receipt(TenantId tenant, ActorId actor, UUID request, @Nullable String hash) {
        return jdbc.sql("""
                SELECT * FROM sharepoint_selection_operations
                WHERE tenant_id = :tenant AND actor_id = :actor AND request_id = :request
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("request", request)
                .query((r, n) -> {
                    if (hash != null && !hash.equals(r.getString("request_hash"))) {
                        throw SourceException.conflict("Selection request ID was already used with different content");
                    }
                    return new SelectionReceipt(new SourceId(r.getObject("source_id", UUID.class)), operation(r, n));
                }).optional();
    }

    public @Nullable SourceOperationView pending(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT * FROM sharepoint_selection_operations
                WHERE tenant_id = :tenant AND source_id = :source AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                """).param("tenant", tenant.value()).param("source", source.value())
                .query(this::operation).optional().orElse(null);
    }

    public void cancelForSource(TenantId tenant, SourceId source) {
        jdbc.sql("""
                UPDATE sharepoint_selection_operations SET status = 'CANCELLED', error_code = 'SOURCE_DELETING',
                    completed_at = CURRENT_TIMESTAMP, claim_token = NULL, lease_expires_at = NULL
                WHERE tenant_id = :tenant AND source_id = :source AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                """).param("tenant", tenant.value()).param("source", source.value()).update();
    }

    /** Accepts a scope request, superseding whatever was still pending for that Source. */
    public SelectionReceipt submit(TenantId tenant, ActorId actor, UUID request, String hash, SourceId source,
            CredentialId credential, long credentialRevision, long scopeRevision, Scope scope, @Nullable String name,
            @Nullable SourceAccess access, List<GroupId> groupIds, SelectionPolicy policy) {
        jdbc.sql("""
                UPDATE sharepoint_selection_operations SET status = 'SUPERSEDED', completed_at = CURRENT_TIMESTAMP,
                    claim_token = NULL, lease_expires_at = NULL, error_code = 'SELECTION_SUPERSEDED'
                WHERE tenant_id = :tenant AND source_id = :source AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                """).param("tenant", tenant.value()).param("source", source.value()).update();
        UUID id = UUID.randomUUID();
        var trace = SourceOperationTraceContext.current();
        jdbc.sql("""
                INSERT INTO sharepoint_selection_operations (id, tenant_id, source_id, actor_id, request_id,
                    request_hash, credential_id, credential_revision, scope_revision, scope_mode, source_name,
                    access_type, group_ids, include_documents, include_pages, sync_interval_minutes,
                    prune_interval_hours, max_requests, max_roots, max_request_bytes, origin_trace_id, origin_span_id)
                VALUES (:id, :tenant, :source, :actor, :request, :hash, :credential, :credentialRevision, :scope,
                    :mode, :name, :access, CAST(:groups AS jsonb), :documents, :pages, :sync, :prune,
                    :requests, :roots, :bytes, :trace, :span)
                """).param("id", id).param("tenant", tenant.value()).param("source", source.value())
                .param("actor", actor.value()).param("request", request).param("hash", hash)
                .param("credential", credential.value()).param("credentialRevision", credentialRevision)
                .param("scope", scopeRevision).param("mode", scope.scopeMode().name()).param("name", name)
                .param("access", access == null ? null : access.name())
                .param("groups", groupIds.stream().map(group -> "\"" + group.value() + "\"")
                        .collect(Collectors.joining(",", "[", "]")))
                .param("documents", scope.includeDocuments()).param("pages", scope.includePages())
                .param("sync", scope.syncIntervalMinutes()).param("prune", scope.pruneIntervalHours())
                .param("requests", Math.min(100_000, policy.maxRootsPerSource() * 8 + 4096))
                .param("roots", policy.maxRootsPerSource()).param("bytes", policy.maxRequestBytes())
                .param("trace", trace == null ? null : trace.traceId())
                .param("span", trace == null ? null : trace.spanId()).update();
        insert(tenant, id, ROOT, scope.siteUrls());
        insert(tenant, id, EXCLUDED_SITE, scope.excludedSites());
        insert(tenant, id, EXCLUDED_PATH, scope.excludedPaths());
        return new SelectionReceipt(source, find(tenant, new SourceOperationId(id)).orElseThrow());
    }

    private void insert(TenantId tenant, UUID operation, String kind, List<String> values) {
        int position = 0;
        for (String value : values) {
            jdbc.sql("""
                    INSERT INTO sharepoint_selection_entries (tenant_id, operation_id, position, kind, value)
                    VALUES (:tenant, :operation, :position, :kind, :value)
                    """).param("tenant", tenant.value()).param("operation", operation).param("position", position++)
                    .param("kind", kind).param("value", value).update();
        }
    }

    public Optional<Work> claim(TenantId tenant, SourceOperationId id, UUID delivery) {
        // Verification for one credential runs one at a time, so a Tenant cannot flood Microsoft from here.
        var credential = jdbc.sql("""
                SELECT credential_id FROM sharepoint_selection_operations WHERE tenant_id = :tenant AND id = :id
                """).param("tenant", tenant.value()).param("id", id.value()).query(UUID.class).optional();
        if (credential.isPresent()) {
            jdbc.sql("SELECT id FROM credentials WHERE tenant_id = :tenant AND id = :id FOR UPDATE")
                    .param("tenant", tenant.value()).param("id", credential.get()).query(UUID.class).optional();
            boolean busy = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM sharepoint_selection_operations
                        WHERE tenant_id = :tenant AND credential_id = :credential AND id <> :id
                          AND status = 'IN_PROGRESS' AND lease_expires_at > CURRENT_TIMESTAMP)
                    """).param("tenant", tenant.value()).param("credential", credential.get())
                    .param("id", id.value()).query(Boolean.class).single();
            if (busy) {
                jdbc.sql("""
                        UPDATE sharepoint_selection_operations SET status = 'NOT_STARTED', claim_token = NULL,
                            lease_expires_at = NULL, delivery_id = NULL, dispatch_token = NULL,
                            dispatch_lease_expires_at = NULL, redis_message_id = NULL, dispatched_at = NULL,
                            next_dispatch_at = CURRENT_TIMESTAMP + INTERVAL '5 seconds'
                        WHERE tenant_id = :tenant AND id = :id AND delivery_id = :delivery
                          AND (status = 'NOT_STARTED'
                               OR status = 'IN_PROGRESS' AND lease_expires_at <= CURRENT_TIMESTAMP)
                        """).param("tenant", tenant.value()).param("id", id.value()).param("delivery", delivery).update();
                return Optional.empty();
            }
        }
        return WorkLeases.claim(jdbc, "sharepoint_selection_operations", tenant.value(), id.value(), delivery,
                (operation, token) -> jdbc.sql("""
                        SELECT * FROM sharepoint_selection_operations WHERE tenant_id = :tenant AND id = :id
                        """).param("tenant", tenant.value()).param("id", operation)
                        .query((r, _) -> new Work(tenant, new SourceId(r.getObject("source_id", UUID.class)), id, token,
                                WorkLeases.initialQueueWait(r))).single());
    }

    public boolean renew(Work work) {
        return WorkLeases.renew(jdbc, "sharepoint_selection_operations", work.tenantId().value(),
                work.operationId().value(), work.claimToken());
    }

    public boolean current(Work work) {
        return jdbc.sql("""
                SELECT id FROM sharepoint_selection_operations
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token AND status = 'IN_PROGRESS'
                  AND lease_expires_at > CURRENT_TIMESTAMP FOR UPDATE
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("token", work.claimToken()).query(UUID.class).optional().isPresent();
    }

    public Intent intent(Work work) {
        return jdbc.sql("SELECT * FROM sharepoint_selection_operations WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .query((r, _) -> new Intent(new ActorId(r.getObject("actor_id", UUID.class)),
                        r.getObject("credential_id", UUID.class), r.getLong("credential_revision"),
                        r.getLong("scope_revision"), ScopeMode.valueOf(r.getString("scope_mode")),
                        r.getString("source_name"),
                        r.getString("access_type") == null ? null : SourceAccess.valueOf(r.getString("access_type")),
                        groupIds(work), r.getBoolean("include_documents"), r.getBoolean("include_pages"),
                        r.getInt("sync_interval_minutes"), r.getInt("prune_interval_hours"))).single();
    }

    private List<GroupId> groupIds(Work work) {
        return jdbc.sql("""
                SELECT value FROM sharepoint_selection_operations,
                  jsonb_array_elements_text(group_ids) AS selected(value)
                WHERE tenant_id = :tenant AND id = :id ORDER BY value
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .query((r, _) -> new GroupId(UUID.fromString(r.getString("value")))).list();
    }

    public List<Entry> entries(Work work) {
        return jdbc.sql("""
                SELECT * FROM sharepoint_selection_entries WHERE tenant_id = :tenant AND operation_id = :id
                ORDER BY kind, position
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .query((r, _) -> new Entry(r.getInt("position"), r.getString("kind"), r.getString("value"),
                        r.getBoolean("verified"), r.getString("root_kind"), r.getString("site_id"),
                        r.getString("drive_id"), r.getString("item_id"), r.getString("display_name"))).list();
    }

    /** Records one resolved root; the claim must still be current, so a lost lease cannot write. */
    public void verified(Work work, Entry entry, String rootKind, String siteId, @Nullable String driveId,
            @Nullable String itemId, @Nullable String displayName) {
        jdbc.sql("""
                UPDATE sharepoint_selection_entries SET verified = TRUE, root_kind = :rootKind, site_id = :site,
                    drive_id = :drive, item_id = :item, display_name = :name
                WHERE tenant_id = :tenant AND operation_id = :operation AND kind = :kind AND position = :position
                """).param("rootKind", rootKind).param("site", siteId).param("drive", driveId).param("item", itemId)
                .param("name", displayName).param("tenant", work.tenantId().value())
                .param("operation", work.operationId().value()).param("kind", entry.kind())
                .param("position", entry.position()).update();
    }

    public void reserveRequest(Work work) {
        if (jdbc.sql("""
                UPDATE sharepoint_selection_operations SET request_count = request_count + 1
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token AND status = 'IN_PROGRESS'
                  AND lease_expires_at > CURRENT_TIMESTAMP AND request_count < max_requests AND elapsed_millis < 3600000
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("token", work.claimToken()).update() != 1) {
            throw SourceException.invalid("The scope verification exceeds its budget.",
                    "SharePoint selection operation budget exceeded");
        }
    }

    public void finish(Work work, String status, @Nullable String code) {
        jdbc.sql("""
                UPDATE sharepoint_selection_operations SET status = :status, error_code = :code,
                    completed_at = CURRENT_TIMESTAMP, claim_token = NULL, lease_expires_at = NULL
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token AND status = 'IN_PROGRESS'
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """).param("status", status).param("code", code).param("tenant", work.tenantId().value())
                .param("id", work.operationId().value()).param("token", work.claimToken()).update();
    }

    public void continueLater(Work work, long elapsed, @Nullable String error) {
        jdbc.sql("""
                UPDATE sharepoint_selection_operations
                SET status = CASE WHEN failure_attempts + :failure >= 5 THEN 'FAILED' ELSE 'NOT_STARTED' END,
                    completed_at = CASE WHEN failure_attempts + :failure >= 5 THEN CURRENT_TIMESTAMP ELSE NULL END,
                    elapsed_millis = elapsed_millis + :elapsed, failure_attempts = failure_attempts + :failure,
                    error_code = :error, claim_token = NULL, lease_expires_at = NULL, delivery_id = NULL,
                    redis_message_id = NULL, dispatched_at = NULL, dispatch_token = NULL,
                    dispatch_lease_expires_at = NULL,
                    next_dispatch_at = CURRENT_TIMESTAMP + :seconds * INTERVAL '1 second'
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token AND status = 'IN_PROGRESS'
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """).param("failure", error == null ? 0 : 1).param("elapsed", elapsed).param("error", error)
                .param("seconds", error == null ? 0 : 30).param("tenant", work.tenantId().value())
                .param("id", work.operationId().value()).param("token", work.claimToken()).update();
    }

    private SourceOperationView operation(ResultSet r, int ignored) throws SQLException {
        return new SourceOperationView(new SourceOperationId(r.getObject("id", UUID.class)),
                SourceOperationType.VALIDATE_SHAREPOINT_SELECTION,
                JdbcSourceRepository.operationStatus(r.getString("status")),
                r.getTimestamp("created_at").toInstant(), JdbcSourceRepository.instant(r, "completed_at"),
                r.getString("error_code"));
    }

    /** What the accepted request asked for; {@code name} and {@code access} are set only when creating a Source. */
    public record Intent(ActorId actorId, @Nullable UUID credentialId, long credentialRevision, long scopeRevision,
                         ScopeMode scopeMode, @Nullable String name, @Nullable SourceAccess access,
                         List<GroupId> groupIds, boolean includeDocuments, boolean includePages,
                         int syncIntervalMinutes, int pruneIntervalHours) {
        public Intent { groupIds = List.copyOf(groupIds); }
    }

    public record Entry(int position, String kind, String value, boolean verified, @Nullable String rootKind,
                        @Nullable String siteId, @Nullable String driveId, @Nullable String itemId,
                        @Nullable String displayName) {
        public boolean root() { return ROOT.equals(kind); }
    }
}
