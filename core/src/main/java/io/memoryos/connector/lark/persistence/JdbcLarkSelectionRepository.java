package io.memoryos.connector.lark.persistence;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkSourceService.RootKind;
import io.memoryos.connector.LarkSourceService.RootView;
import io.memoryos.connector.LarkSourceService.SelectionReceipt;
import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.SourceSelectionProcessor.Work;
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
public class JdbcLarkSelectionRepository {
    /** A root costs one request to verify; the margin covers what a retried batch repeats. */
    private static final int REQUESTS_PER_ROOT = 4;
    private static final int MAX_REQUEST_BYTES = 262_144;

    private final JdbcClient jdbc;
    private final SelectionOperations operations;

    public JdbcLarkSelectionRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.operations = new SelectionOperations(jdbc, SourceType.LARK);
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
            CredentialId credential, long credentialRevision, long scopeRevision, List<RootView> roots,
            int syncIntervalMinutes, @Nullable String name, @Nullable SourceAccess access, List<GroupId> groupIds,
            int maxRoots) {
        operations.supersedePending(tenant, source);
        var trace = SourceOperationTraceContext.current();
        UUID id = operations.insert(tenant, actor, request, hash, source, credential.value(), credentialRevision,
                scopeRevision, name, access == null ? null : access.name(),
                groupIds.stream().map(group -> "\"" + group.value() + "\"").collect(Collectors.joining(",", "[", "]")),
                new SelectionOperations.Budget(maxRoots * REQUESTS_PER_ROOT + 16, maxRoots, MAX_REQUEST_BYTES),
                trace == null ? null : trace.traceId(), trace == null ? null : trace.spanId());
        jdbc.sql("""
                INSERT INTO lark_selection_details (tenant_id, operation_id, sync_interval_minutes)
                VALUES (:tenant, :operation, :sync)
                """).param("tenant", tenant.value()).param("operation", id).param("sync", syncIntervalMinutes).update();
        int position = 0;
        for (RootView root : roots) {
            jdbc.sql("""
                    INSERT INTO lark_selection_entries (tenant_id, operation_id, position, kind, token)
                    VALUES (:tenant, :operation, :position, :kind, :token)
                    """).param("tenant", tenant.value()).param("operation", id).param("position", position++)
                    .param("kind", root.kind().name()).param("token", root.token()).update();
        }
        return new SelectionReceipt(source, operations.find(tenant, new SourceOperationId(id)).orElseThrow());
    }

    /** The lifecycle this provider's selection operations share with every other provider's. */
    public SelectionOperations operations() {
        return operations;
    }

    public boolean current(Work work) {
        return operations.current(work);
    }

    public Intent intent(Work work) {
        return jdbc.sql("""
                SELECT o.*, d.sync_interval_minutes
                FROM source_selection_operations o
                JOIN lark_selection_details d ON d.tenant_id = o.tenant_id AND d.operation_id = o.id
                WHERE o.tenant_id = :tenant AND o.id = :id
                """)
                .param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .query((r, _) -> new Intent(new ActorId(r.getObject("actor_id", UUID.class)),
                        r.getObject("credential_id", UUID.class), r.getLong("credential_revision"),
                        r.getLong("scope_revision"), r.getString("source_name"),
                        r.getString("access_type") == null ? null : SourceAccess.valueOf(r.getString("access_type")),
                        groupIds(work), r.getInt("sync_interval_minutes"))).single();
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
                SELECT * FROM lark_selection_entries WHERE tenant_id = :tenant AND operation_id = :id
                ORDER BY position
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .query((r, _) -> new Entry(r.getInt("position"), RootKind.valueOf(r.getString("kind")),
                        r.getString("token"), r.getBoolean("verified"), r.getString("display_name"))).list();
    }

    /** Records one root the app read; the caller holds the current claim. */
    public void verified(Work work, Entry entry, @Nullable String displayName) {
        jdbc.sql("""
                UPDATE lark_selection_entries SET verified = TRUE, display_name = :name
                WHERE tenant_id = :tenant AND operation_id = :operation AND position = :position
                """).param("name", displayName == null || displayName.isBlank() ? null
                        : displayName.length() > 255 ? displayName.substring(0, 255) : displayName)
                .param("tenant", work.tenantId().value()).param("operation", work.operationId().value())
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
                    "Lark selection operation budget exceeded");
        }
    }

    public void finish(Work work, String status, @Nullable String code) {
        operations.finish(work, status, code);
    }

    /** What the accepted request asked for; {@code name} and {@code access} are set only when creating a Source. */
    public record Intent(ActorId actorId, @Nullable UUID credentialId, long credentialRevision, long scopeRevision,
                         @Nullable String name, @Nullable SourceAccess access, List<GroupId> groupIds,
                         int syncIntervalMinutes) {
        public Intent { groupIds = List.copyOf(groupIds); }
    }

    public record Entry(int position, RootKind kind, String token, boolean verified, @Nullable String displayName) {}
}
