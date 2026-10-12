package io.memoryos.connector.sync.persistence;

import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.SourceSelectionProcessor.Work;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The lifecycle every provider's selection operations share: receipts, the pending request of a Source, the
 * claim that verifies one credential's requests one at a time, and how verification ends or continues. Every
 * provider's requests share {@code source_selection_operations}; an instance serves the rows of one
 * {@link SourceType}, and the provider keeps what only it verifies in its own details table.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public final class SelectionOperations {
    /** A batch that failed this often fails its operation. */
    private static final int MAX_FAILURES = 5;
    private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(30);
    /** How long a request waits while another request of its credential is being verified. */
    private static final Duration BUSY_CREDENTIAL = Duration.ofSeconds(5);
    private static final String TABLE = "source_selection_operations";

    private final JdbcClient jdbc;
    private final SourceType sourceType;
    private final SourceOperationType type;

    public SelectionOperations(JdbcClient jdbc, SourceType sourceType) {
        this.jdbc = jdbc;
        this.sourceType = sourceType;
        this.type = SourceOperationType.selectionValidation(sourceType);
    }

    /** The provider whose adapter verifies the request, or empty when the request no longer exists. */
    static Optional<SourceType> sourceType(JdbcClient jdbc, TenantId tenant, SourceOperationId operation) {
        return jdbc.sql("SELECT source_type FROM source_selection_operations WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.value()).param("id", operation.value())
                .query(String.class).optional().map(SourceType::valueOf);
    }

    public Optional<SourceOperationView> find(TenantId tenant, SourceOperationId operation) {
        return jdbc.sql("""
                SELECT * FROM source_selection_operations
                WHERE tenant_id = :tenant AND id = :id AND source_type = :type
                """).param("tenant", tenant.value()).param("id", operation.value())
                .param("type", sourceType.name()).query(this::operation).optional();
    }

    /**
     * The request an actor already submitted under {@code request}, so a retry never starts a second one. A
     * {@code hash} that differs from the stored one is a conflict; null skips that check.
     */
    public Optional<Receipt> receipt(TenantId tenant, ActorId actor, UUID request, @Nullable String hash) {
        return jdbc.sql("""
                SELECT * FROM source_selection_operations
                WHERE tenant_id = :tenant AND actor_id = :actor AND source_type = :type AND request_id = :request
                """).param("tenant", tenant.value()).param("actor", actor.value())
                .param("type", sourceType.name()).param("request", request)
                .query((r, n) -> {
                    if (hash != null && !hash.equals(r.getString("request_hash"))) {
                        throw SourceException.conflict("Selection request ID was already used with different content");
                    }
                    return new Receipt(new SourceId(r.getObject("source_id", UUID.class)), operation(r, n));
                }).optional();
    }

    public @Nullable SourceOperationView pending(TenantId tenant, SourceId source) {
        return jdbc.sql("""
                SELECT * FROM source_selection_operations
                WHERE tenant_id = :tenant AND source_id = :source AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                """).param("tenant", tenant.value()).param("source", source.value())
                .query(this::operation).optional().orElse(null);
    }

    /** Ends the request still pending for the Source, which a newer one replaces. */
    public void supersedePending(TenantId tenant, SourceId source) {
        end(tenant, source, "SUPERSEDED", "SELECTION_SUPERSEDED");
    }

    private void end(TenantId tenant, SourceId source, String status, String code) {
        jdbc.sql("UPDATE source_selection_operations SET status = :status, error_code = :code, "
                        + "completed_at = CURRENT_TIMESTAMP, " + WorkLeases.RELEASE + """

                WHERE tenant_id = :tenant AND source_id = :source AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                """).param("status", status).param("code", code)
                .param("tenant", tenant.value()).param("source", source.value()).update();
    }

    /**
     * Records a newly accepted request and returns its identifier; the provider then writes its details row and
     * entries.
     */
    public UUID insert(TenantId tenant, ActorId actor, UUID request, String hash, SourceId source, UUID credential,
            long credentialRevision, long scopeRevision, @Nullable String name, @Nullable String access,
            String groupIds, Budget budget, @Nullable String traceId, @Nullable String spanId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO source_selection_operations (id, tenant_id, source_id, source_type, actor_id, request_id,
                    request_hash, credential_id, credential_revision, scope_revision, source_name, access_type,
                    group_ids, max_requests, max_roots, max_request_bytes, origin_trace_id, origin_span_id)
                VALUES (:id, :tenant, :source, :type, :actor, :request, :hash, :credential, :credentialRevision,
                    :scope, :name, :access, CAST(:groups AS jsonb), :requests, :roots, :bytes, :trace, :span)
                """).param("id", id).param("tenant", tenant.value()).param("source", source.value())
                .param("type", sourceType.name()).param("actor", actor.value()).param("request", request)
                .param("hash", hash).param("credential", credential).param("credentialRevision", credentialRevision)
                .param("scope", scopeRevision).param("name", name).param("access", access, Types.VARCHAR)
                .param("groups", groupIds).param("requests", budget.maxRequests()).param("roots", budget.maxRoots())
                .param("bytes", budget.maxRequestBytes()).param("trace", traceId).param("span", spanId).update();
        return id;
    }

    /**
     * Claims a delivered request. Requests of one credential are verified one at a time, so a Tenant cannot flood
     * its provider: while another claim of the same credential is live, this delivery is handed back briefly.
     * Serialising on the credential row holds no provider call inside a transaction.
     */
    public Optional<Work> claim(TenantId tenant, SourceOperationId id, UUID delivery) {
        if (sourceType(jdbc, tenant, id).filter(sourceType::equals).isEmpty()) return Optional.empty();
        var credential = jdbc.sql("""
                SELECT credential_id FROM source_selection_operations WHERE tenant_id = :tenant AND id = :id
                """).param("tenant", tenant.value()).param("id", id.value()).query(UUID.class).optional();
        if (credential.isPresent()) {
            jdbc.sql("SELECT id FROM credentials WHERE tenant_id = :tenant AND id = :id FOR UPDATE")
                    .param("tenant", tenant.value()).param("id", credential.get()).query(UUID.class).optional();
            boolean busy = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM source_selection_operations
                     WHERE tenant_id = :tenant AND credential_id = :credential AND id <> :id
                      AND status = 'IN_PROGRESS' AND lease_expires_at > CURRENT_TIMESTAMP)
                    """).param("tenant", tenant.value()).param("credential", credential.get())
                    .param("id", id.value()).query(Boolean.class).single();
            if (busy) {
                jdbc.sql("UPDATE source_selection_operations SET status = 'NOT_STARTED', " + WorkLeases.RELEASE + """
                        ,
                            next_dispatch_at = CURRENT_TIMESTAMP + :waitMillis * INTERVAL '1 millisecond'
                        WHERE tenant_id = :tenant AND id = :id AND delivery_id = :delivery
                          AND (status = 'NOT_STARTED'
                               OR status = 'IN_PROGRESS' AND lease_expires_at <= CURRENT_TIMESTAMP)
                        """).param("waitMillis", BUSY_CREDENTIAL.toMillis()).param("tenant", tenant.value())
                        .param("id", id.value()).param("delivery", delivery).update();
                return Optional.empty();
            }
        }
        return WorkLeases.claim(jdbc, TABLE, tenant.value(), id.value(), delivery, (operation, token) ->
                jdbc.sql("SELECT * FROM source_selection_operations WHERE tenant_id = :tenant AND id = :id")
                        .param("tenant", tenant.value()).param("id", operation)
                        .query((r, _) -> new Work(tenant, new SourceId(r.getObject("source_id", UUID.class)), id, token,
                                WorkLeases.initialQueueWait(r), sourceType)).single());
    }

    public boolean renew(Work work) {
        return WorkLeases.renew(jdbc, TABLE, work.tenantId().value(), work.operationId().value(), work.claimToken());
    }

    /** Whether the claim still holds a live lease; locks the operation row. */
    public boolean current(Work work) {
        return jdbc.sql("""
                SELECT id FROM source_selection_operations
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token AND status = 'IN_PROGRESS'
                  AND lease_expires_at > CURRENT_TIMESTAMP FOR UPDATE
                """).param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("token", work.claimToken()).query(UUID.class).optional().isPresent();
    }

    public void finish(Work work, String status, @Nullable String code) {
        jdbc.sql("""
                UPDATE source_selection_operations
                SET status = :status, error_code = :code, completed_at = CURRENT_TIMESTAMP,
                    claim_token = NULL, lease_expires_at = NULL
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token AND status = 'IN_PROGRESS'
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """).param("status", status).param("code", code).param("tenant", work.tenantId().value())
                .param("id", work.operationId().value()).param("token", work.claimToken()).update();
    }

    /**
     * Hands the request back after a batch: at once when the batch only ran out of time, or after a backoff when
     * it failed with {@code error}. The operation fails after {@value #MAX_FAILURES} failed batches; the time
     * every batch spent counts against the operation's budget.
     */
    public void continueLater(Work work, long elapsedMillis, @Nullable String error) {
        jdbc.sql("""
                UPDATE source_selection_operations
                SET status = CASE WHEN failure_attempts + :failure >= :maxFailures THEN 'FAILED' ELSE 'NOT_STARTED' END,
                    completed_at = CASE WHEN failure_attempts + :failure >= :maxFailures THEN CURRENT_TIMESTAMP ELSE NULL END,
                    elapsed_millis = elapsed_millis + :elapsed, failure_attempts = failure_attempts + :failure,
                    error_code = :error,
                """ + WorkLeases.RELEASE + """
                ,
                    next_dispatch_at = CURRENT_TIMESTAMP + :backoffMillis * INTERVAL '1 millisecond'
                WHERE tenant_id = :tenant AND id = :id AND claim_token = :token AND status = 'IN_PROGRESS'
                  AND lease_expires_at > CURRENT_TIMESTAMP
                """).param("failure", error == null ? 0 : 1).param("maxFailures", MAX_FAILURES)
                .param("elapsed", elapsedMillis).param("error", error)
                .param("backoffMillis", error == null ? 0 : FAILURE_BACKOFF.toMillis())
                .param("tenant", work.tenantId().value()).param("id", work.operationId().value())
                .param("token", work.claimToken()).update();
    }

    public SourceOperationView operation(ResultSet r, int ignored) throws SQLException {
        return new SourceOperationView(new SourceOperationId(r.getObject("id", UUID.class)), type,
                JdbcSourceRepository.operationStatus(r.getString("status")), r.getTimestamp("created_at").toInstant(),
                JdbcSourceRepository.instant(r, "completed_at"), r.getString("error_code"));
    }

    /** The Source a request targets and where its verification stands. */
    public record Receipt(SourceId sourceId, SourceOperationView operation) {}

    /** What one verification may spend before it fails. */
    public record Budget(int maxRequests, int maxRoots, int maxRequestBytes) {}
}
