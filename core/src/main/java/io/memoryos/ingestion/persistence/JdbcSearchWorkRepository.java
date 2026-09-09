package io.memoryos.ingestion.persistence;

import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.document.DocumentChanged;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.TenantId;
import io.memoryos.ingestion.OperationDelivery;
import java.sql.Types;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcSearchWorkRepository {
    private final JdbcClient jdbc;
    public JdbcSearchWorkRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void enqueue(DocumentChanged event, String identity, boolean repair) {
        var origin = SourceOperationTraceContext.current();
        jdbc.sql("""
                INSERT INTO search_index_operations(id,tenant_id,document_id,generation,action,index_identity,
                    origin_trace_id,origin_span_id)
                VALUES (:id,:tenant,:document,:generation,:action,:identity,:trace,:span)
                ON CONFLICT (tenant_id,document_id,generation,action,index_identity) DO UPDATE
                SET status='NOT_STARTED',processing_attempts=0,error_code=NULL,completed_at=NULL,
                    next_dispatch_at=CURRENT_TIMESTAMP,dispatch_token=NULL,dispatch_lease_expires_at=NULL
                WHERE :repair AND (search_index_operations.status='SUCCESS' OR
                    (search_index_operations.status='FAILED' AND
                     search_index_operations.completed_at < CURRENT_TIMESTAMP - INTERVAL '15' MINUTE))
                """).param("id", UUID.randomUUID()).param("tenant", event.tenantId().value())
                .param("document", event.documentId().value()).param("generation", event.generation())
                .param("action", event.removed() ? "DELETE" : "INDEX").param("identity", identity)
                .param("trace", origin == null ? null : origin.traceId(), Types.VARCHAR)
                .param("span", origin == null ? null : origin.spanId(), Types.VARCHAR)
                .param("repair", repair).update();
    }

    @Transactional
    public Optional<Claim> claim(OperationDelivery delivery, String identity) {
        UUID token = UUID.randomUUID();
        return jdbc.sql("""
                UPDATE search_index_operations w SET status='IN_PROGRESS',claim_token=:token,
                    lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '2' MINUTE,
                    started_at=COALESCE(started_at,CURRENT_TIMESTAMP),processing_attempts=processing_attempts+1
                WHERE w.tenant_id=:tenant AND w.id=:id AND w.delivery_id=:delivery AND w.index_identity=:identity
                    AND (w.status='NOT_STARTED' OR (w.status='IN_PROGRESS' AND w.lease_expires_at<CURRENT_TIMESTAMP))
                    AND (w.action='DELETE' OR EXISTS (SELECT 1 FROM tenants t WHERE t.id=w.tenant_id AND t.status='ACTIVE'))
                RETURNING w.document_id,w.generation,w.action,w.processing_attempts
                """).param("token", token).param("tenant", delivery.tenantId().value())
                .param("id", delivery.operationId().value()).param("delivery", delivery.deliveryId()).param("identity", identity)
                .query((rs, _) -> new Claim(delivery.tenantId(), delivery.operationId().value(), token,
                        new DocumentId(rs.getObject("document_id", UUID.class)), rs.getObject("generation", UUID.class),
                        "DELETE".equals(rs.getString("action")), rs.getInt("processing_attempts"))).optional();
    }

    public boolean renew(Claim claim) {
        return jdbc.sql("""
                UPDATE search_index_operations SET lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '2' MINUTE
                WHERE id=:id AND tenant_id=:tenant AND claim_token=:token AND status='IN_PROGRESS'
                    AND lease_expires_at>=CURRENT_TIMESTAMP
                """).param("id", claim.id()).param("tenant", claim.tenantId().value()).param("token", claim.token()).update() == 1;
    }

    @Transactional
    public boolean finish(Claim claim, String status, String error) {
        if (!Set.of("SUCCESS", "CANCELLED", "FAILED", "NOT_STARTED").contains(status)) {
            throw new IllegalArgumentException("Invalid search work outcome");
        }
        return jdbc.sql("""
                UPDATE search_index_operations SET status=:status,error_code=:error,claim_token=NULL,lease_expires_at=NULL,
                    completed_at=CASE WHEN :status='NOT_STARTED' THEN NULL ELSE CURRENT_TIMESTAMP END,
                    next_dispatch_at=CURRENT_TIMESTAMP + INTERVAL '30' SECOND,
                    dispatch_token=NULL,dispatch_lease_expires_at=NULL
                WHERE id=:id AND tenant_id=:tenant AND claim_token=:token AND status='IN_PROGRESS'
                    AND lease_expires_at>=CURRENT_TIMESTAMP
                """).param("id", claim.id()).param("tenant", claim.tenantId().value()).param("token", claim.token())
                .param("status", status).param("error", error, Types.VARCHAR).update() == 1;
    }

    @Transactional
    public void cancelObsolete(String identity) {
        jdbc.sql("""
                UPDATE search_index_operations w SET status='CANCELLED',claim_token=NULL,lease_expires_at=NULL,
                    completed_at=CURRENT_TIMESTAMP,error_code='SEARCH_OBSOLETE',dispatch_token=NULL,dispatch_lease_expires_at=NULL
                WHERE w.id IN (SELECT candidate.id FROM search_index_operations candidate
                    JOIN tenants t ON t.id=candidate.tenant_id
                    WHERE candidate.status IN ('NOT_STARTED','IN_PROGRESS')
                        AND (candidate.index_identity<>:identity OR (candidate.action='INDEX' AND t.status<>'ACTIVE'))
                    ORDER BY candidate.created_at LIMIT 100 FOR UPDATE OF candidate SKIP LOCKED)
                """).param("identity", identity).update();
    }

    public record Claim(TenantId tenantId, UUID id, UUID token, DocumentId documentId,
            UUID generation, boolean removed, int attempts) { }
}
