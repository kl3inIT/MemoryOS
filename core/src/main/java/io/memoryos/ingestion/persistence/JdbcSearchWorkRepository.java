package io.memoryos.ingestion.persistence;

import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.document.DocumentChanged;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.ingestion.OperationDelivery;
import java.sql.Types;
import java.util.Collection;
import java.util.List;
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
                WHERE :repair AND (search_index_operations.status IN ('SUCCESS','CANCELLED') OR
                    (search_index_operations.status='FAILED' AND
                     search_index_operations.completed_at < CURRENT_TIMESTAMP - INTERVAL '15' MINUTE))
                """).param("id", UUID.randomUUID()).param("tenant", event.tenantId().value())
                .param("document", event.documentId().value()).param("generation", event.generation())
                .param("action", event.removed() ? "DELETE" : "INDEX").param("identity", identity)
                .param("trace", origin == null ? null : origin.traceId(), Types.VARCHAR)
                .param("span", origin == null ? null : origin.spanId(), Types.VARCHAR)
                .param("repair", repair).update();
    }

    /**
     * Queues an access refresh for every searchable document mapped to the Source, in the caller's transaction.
     * A pending or running refresh is reset so the next claim reads the newest access; its old claim cannot finish.
     */
    @Transactional
    public void enqueueSourceAccess(TenantId tenant, SourceId source, String identity) {
        jdbc.sql("""
                INSERT INTO search_index_operations(id,tenant_id,document_id,generation,action,index_identity)
                SELECT gen_random_uuid(),d.tenant_id,d.id,sp.generation,'ACCESS',:identity FROM documents d
                JOIN document_search_projection sp ON sp.tenant_id=d.tenant_id AND sp.document_id=d.id AND sp.index_identity=:identity
                WHERE d.tenant_id=:tenant AND d.status='ELIGIBLE'
                    AND EXISTS (SELECT 1 FROM documents_by_connector_credential_pair m
                        WHERE m.tenant_id=d.tenant_id AND m.document_id=d.id AND m.connector_credential_pair_id=:source)
                ON CONFLICT (tenant_id,document_id,generation,action,index_identity) DO UPDATE
                SET status='NOT_STARTED',processing_attempts=0,error_code=NULL,completed_at=NULL,claim_token=NULL,
                    lease_expires_at=NULL,next_dispatch_at=CURRENT_TIMESTAMP,dispatch_token=NULL,dispatch_lease_expires_at=NULL
                """).param("tenant", tenant.value()).param("source", source.value()).param("identity", identity).update();
    }

    /**
     * Queues an access refresh for the listed searchable documents when the Source derives access from provider
     * permissions (SYNC); other modes ignore permission changes. Pending refreshes are reset as for Source access.
     */
    @Transactional
    public void enqueueDocumentAccess(TenantId tenant, SourceId source, List<DocumentId> documents, String identity) {
        if (documents.isEmpty()) return;
        jdbc.sql("""
                INSERT INTO search_index_operations(id,tenant_id,document_id,generation,action,index_identity)
                SELECT gen_random_uuid(),d.tenant_id,d.id,sp.generation,'ACCESS',:identity FROM documents d
                JOIN document_search_projection sp ON sp.tenant_id=d.tenant_id AND sp.document_id=d.id AND sp.index_identity=:identity
                WHERE d.tenant_id=:tenant AND d.id IN (:documents) AND d.status='ELIGIBLE'
                    AND EXISTS (SELECT 1 FROM documents_by_connector_credential_pair m
                        JOIN connector_credential_pairs p ON p.tenant_id=m.tenant_id AND p.id=m.connector_credential_pair_id
                        WHERE m.tenant_id=d.tenant_id AND m.document_id=d.id AND p.id=:source AND p.access_type='SYNC')
                ON CONFLICT (tenant_id,document_id,generation,action,index_identity) DO UPDATE
                SET status='NOT_STARTED',processing_attempts=0,error_code=NULL,completed_at=NULL,claim_token=NULL,
                    lease_expires_at=NULL,next_dispatch_at=CURRENT_TIMESTAMP,dispatch_token=NULL,dispatch_lease_expires_at=NULL
                """).param("tenant", tenant.value()).param("source", source.value())
                .param("documents", documents.stream().map(DocumentId::value).toList()).param("identity", identity).update();
    }

    /**
     * Repairs only the access fields of a fully indexed generation. Like INDEX repair, an existing row is reset only
     * after success or a failure older than 15 minutes, so a pending refresh is not restarted by every scan.
     */
    @Transactional
    public void enqueueAccessRepair(TenantId tenant, DocumentId document, UUID generation, String identity) {
        jdbc.sql("""
                INSERT INTO search_index_operations(id,tenant_id,document_id,generation,action,index_identity)
                VALUES (:id,:tenant,:document,:generation,'ACCESS',:identity)
                ON CONFLICT (tenant_id,document_id,generation,action,index_identity) DO UPDATE
                SET status='NOT_STARTED',processing_attempts=0,error_code=NULL,completed_at=NULL,
                    next_dispatch_at=CURRENT_TIMESTAMP,dispatch_token=NULL,dispatch_lease_expires_at=NULL
                WHERE search_index_operations.status='SUCCESS' OR (search_index_operations.status='FAILED'
                    AND search_index_operations.completed_at < CURRENT_TIMESTAMP - INTERVAL '15' MINUTE)
                """).param("id", UUID.randomUUID()).param("tenant", tenant.value()).param("document", document.value())
                .param("generation", generation).param("identity", identity).update();
    }

    /**
     * Queues INDEX work in the rebuilt index for up to {@code window} documents it does not hold at their current
     * content generation, minus the work already outstanding there, and returns how many were queued. The rebuild
     * reads the chunks already stored, so no Source is fetched and nothing is OCRed again. Everything it needs is
     * durable, so a restarted worker resumes where the last one stopped; the small window keeps changes to PRESENT
     * from waiting behind the whole corpus. Work that failed is retried after 15 minutes, as repair does.
     */
    @Transactional
    public int enqueueRebuild(String identity, int window) {
        int outstanding = jdbc.sql("""
                SELECT COUNT(*) FROM search_index_operations
                WHERE index_identity=:identity AND status IN ('NOT_STARTED','IN_PROGRESS')
                """).param("identity", identity).query(Integer.class).single();
        int room = window - outstanding;
        if (room <= 0) return 0;
        return jdbc.sql("""
                INSERT INTO search_index_operations(id,tenant_id,document_id,generation,action,index_identity)
                SELECT gen_random_uuid(),d.tenant_id,d.id,d.content_generation,'INDEX',:identity
                FROM documents d JOIN tenants t ON t.id=d.tenant_id
                WHERE d.status='ELIGIBLE' AND t.status='ACTIVE' AND d.extraction_artifact_id IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM document_search_projection p WHERE p.tenant_id=d.tenant_id
                        AND p.document_id=d.id AND p.index_identity=:identity AND p.generation=d.content_generation)
                    AND NOT EXISTS (SELECT 1 FROM search_index_operations w WHERE w.tenant_id=d.tenant_id
                        AND w.document_id=d.id AND w.generation=d.content_generation AND w.action='INDEX'
                        AND w.index_identity=:identity AND (w.status IN ('NOT_STARTED','IN_PROGRESS')
                            OR w.status='FAILED' AND w.completed_at >= CURRENT_TIMESTAMP - INTERVAL '15' MINUTE))
                ORDER BY d.tenant_id,d.id LIMIT :room
                ON CONFLICT (tenant_id,document_id,generation,action,index_identity) DO UPDATE
                SET status='NOT_STARTED',processing_attempts=0,error_code=NULL,completed_at=NULL,claim_token=NULL,
                    lease_expires_at=NULL,next_dispatch_at=CURRENT_TIMESTAMP,dispatch_token=NULL,dispatch_lease_expires_at=NULL
                """).param("identity", identity).param("room", room).update();
    }

    /** Claims the delivered work whatever index it is for; the caller refuses an index that is no longer active. */
    @Transactional
    public Optional<Claim> claim(OperationDelivery delivery) {
        UUID token = UUID.randomUUID();
        return jdbc.sql("""
                UPDATE search_index_operations w SET status='IN_PROGRESS',claim_token=:token,
                    lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '2' MINUTE,
                    started_at=COALESCE(started_at,CURRENT_TIMESTAMP),processing_attempts=processing_attempts+1
                WHERE w.tenant_id=:tenant AND w.id=:id AND w.delivery_id=:delivery
                    AND (w.status='NOT_STARTED' OR (w.status='IN_PROGRESS' AND w.lease_expires_at<CURRENT_TIMESTAMP))
                    AND (w.action='DELETE' OR EXISTS (SELECT 1 FROM tenants t WHERE t.id=w.tenant_id AND t.status='ACTIVE'))
                RETURNING w.document_id,w.generation,w.action,w.processing_attempts,w.index_identity
                """).param("token", token).param("tenant", delivery.tenantId().value())
                .param("id", delivery.operationId().value()).param("delivery", delivery.deliveryId())
                .query((rs, _) -> new Claim(delivery.tenantId(), delivery.operationId().value(), token,
                        new DocumentId(rs.getObject("document_id", UUID.class)), rs.getObject("generation", UUID.class),
                        rs.getString("action"), rs.getInt("processing_attempts"), rs.getString("index_identity"))).optional();
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

    /** Cancels work for indexes that are neither PRESENT nor FUTURE any more, in batches of 100. */
    @Transactional
    public void cancelObsolete(Collection<String> identities) {
        jdbc.sql("""
                UPDATE search_index_operations w SET status='CANCELLED',claim_token=NULL,lease_expires_at=NULL,
                    completed_at=CURRENT_TIMESTAMP,error_code='SEARCH_OBSOLETE',dispatch_token=NULL,dispatch_lease_expires_at=NULL
                WHERE w.id IN (SELECT candidate.id FROM search_index_operations candidate
                    JOIN tenants t ON t.id=candidate.tenant_id
                    WHERE candidate.status IN ('NOT_STARTED','IN_PROGRESS')
                        AND (candidate.index_identity NOT IN (:identities) OR (candidate.action<>'DELETE' AND t.status<>'ACTIVE'))
                    ORDER BY candidate.created_at LIMIT 100 FOR UPDATE OF candidate SKIP LOCKED)
                """).param("identities", List.copyOf(identities)).update();
    }

    public record Claim(TenantId tenantId, UUID id, UUID token, DocumentId documentId,
            UUID generation, String action, int attempts, String identity) {
        public boolean removed() { return "DELETE".equals(action); }
        public boolean access() { return "ACCESS".equals(action); }
        public boolean index() { return "INDEX".equals(action); }
    }
}
