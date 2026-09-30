package io.memoryos.connector.sharepoint.persistence;

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
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.sync.persistence.SelectionOperations;
import io.memoryos.iam.GroupId;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
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
    private final SelectionOperations operations;

    public JdbcSharePointSelectionRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.operations = new SelectionOperations(jdbc, SourceType.SHAREPOINT);
    }

    private Optional<SourceOperationView> find(TenantId tenant, SourceOperationId operation) {
        return operations.find(tenant, operation);
    }

    /** Recovers the receipt of a request that was already accepted, so a retry never starts a second one. */
    public Optional<SelectionReceipt> receipt(TenantId tenant, ActorId actor, UUID request, @Nullable String hash) {
        return operations.receipt(tenant, actor, request, hash)
                .map(receipt -> new SelectionReceipt(receipt.sourceId(), receipt.operation()));
    }

    public @Nullable SourceOperationView pending(TenantId tenant, SourceId source) {
        return operations.pending(tenant, source);
    }

    /** Accepts a scope request, superseding whatever was still pending for that Source. */
    public SelectionReceipt submit(TenantId tenant, ActorId actor, UUID request, String hash, SourceId source,
            CredentialId credential, long credentialRevision, long scopeRevision, Scope scope, @Nullable String name,
            @Nullable SourceAccess access, List<GroupId> groupIds, SelectionPolicy policy) {
        operations.supersedePending(tenant, source);
        var trace = SourceOperationTraceContext.current();
        UUID id = operations.insert(tenant, actor, request, hash, source, credential.value(), credentialRevision,
                scopeRevision, name, access == null ? null : access.name(),
                groupIds.stream().map(group -> "\"" + group.value() + "\"").collect(Collectors.joining(",", "[", "]")),
                new SelectionOperations.Budget(Math.min(100_000, policy.maxRootsPerSource() * 8 + 4096),
                        policy.maxRootsPerSource(), policy.maxRequestBytes()),
                trace == null ? null : trace.traceId(), trace == null ? null : trace.spanId());
        jdbc.sql("""
                INSERT INTO sharepoint_selection_details (tenant_id, operation_id, scope_mode, include_documents,
                    include_pages, sync_interval_minutes, prune_interval_hours)
                VALUES (:tenant, :operation, :mode, :documents, :pages, :sync, :prune)
                """).param("tenant", tenant.value()).param("operation", id).param("mode", scope.scopeMode().name())
                .param("documents", scope.includeDocuments()).param("pages", scope.includePages())
                .param("sync", scope.syncIntervalMinutes()).param("prune", scope.pruneIntervalHours()).update();
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

    /** The lifecycle this provider's selection operations share with every other provider's. */
    public SelectionOperations operations() {
        return operations;
    }

    public Optional<Work> claim(TenantId tenant, SourceOperationId id, UUID delivery) {
        return operations.claim(tenant, id, delivery);
    }

    public boolean renew(Work work) {
        return operations.renew(work);
    }

    public boolean current(Work work) {
        return operations.current(work);
    }

    public Intent intent(Work work) {
        return jdbc.sql("""
                SELECT o.*, d.scope_mode, d.include_documents, d.include_pages, d.sync_interval_minutes,
                    d.prune_interval_hours
                FROM source_selection_operations o
                JOIN sharepoint_selection_details d ON d.tenant_id = o.tenant_id AND d.operation_id = o.id
                WHERE o.tenant_id = :tenant AND o.id = :id
                """)
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
                SELECT value FROM source_selection_operations,
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
                UPDATE source_selection_operations SET request_count = request_count + 1
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token AND status = 'IN_PROGRESS'
                  AND lease_expires_at > CURRENT_TIMESTAMP AND request_count < max_requests AND elapsed_millis < 3600000
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("token", work.claimToken()).update() != 1) {
            throw SourceException.invalid("The scope verification exceeds its budget.",
                    "SharePoint selection operation budget exceeded");
        }
    }

    public void finish(Work work, String status, @Nullable String code) {
        operations.finish(work, status, code);
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
