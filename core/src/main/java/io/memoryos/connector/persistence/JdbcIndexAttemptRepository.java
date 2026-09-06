package io.memoryos.connector.persistence;

import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.connector.IndexWork;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.document.DocumentId;
import io.memoryos.tenant.TenantId;

import java.time.Duration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcIndexAttemptRepository implements ConnectorIndexingPort {


    private final JdbcClient jdbcClient;
    private final JdbcSourceRepository sources;
    private final JdbcSourceDocumentRepository sourceDocuments;

    public JdbcIndexAttemptRepository(
            JdbcClient jdbcClient,
            JdbcSourceRepository sources,
            JdbcSourceDocumentRepository sourceDocuments
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.sources = Objects.requireNonNull(sources, "sources must not be null");
        this.sourceDocuments = Objects.requireNonNull(sourceDocuments, "sourceDocuments must not be null");
    }

    public Optional<SourceOperationView> findLive(
            TenantId tenantId,
            SourceId sourceId,
            JdbcSourceItemRepository.ItemVersion itemVersion
    ) {
        return jdbcClient.sql("""
                        SELECT id, status, created_at, completed_at, error_code
                        FROM index_attempts
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_version_id = :versionId
                          AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                        ORDER BY pair_sequence DESC
                        FETCH FIRST 1 ROW ONLY
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .param("versionId", itemVersion.versionId())
                .query((resultSet, ignored) -> operation(resultSet))
                .optional();
    }

    public SourceOperationView create(
            TenantId tenantId,
            JdbcSourceRepository.SourcePair pair,
            JdbcSourceItemRepository.ItemVersion itemVersion
    ) {
        long pairSequence = pair.pairSequence() + 1;
        Long itemSequence = jdbcClient.sql("""
                        SELECT COALESCE(MAX(item_sequence), 0) + 1
                        FROM index_attempts
                        WHERE tenant_id = :tenantId AND connector_item_id = :itemId
                        """)
                .param("tenantId", tenantId.value())
                .param("itemId", itemVersion.itemId().value())
                .query(Long.class)
                .single();
        SourceOperationId attemptId = new SourceOperationId(UUID.randomUUID());
        var trace = SourceOperationTraceContext.current();
        jdbcClient.sql("""
                        UPDATE connector_credential_pairs
                        SET pair_sequence = :pairSequence, status = 'INDEXING',
                            error_code = NULL, updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :pairId
                        """)
                .param("pairSequence", pairSequence)
                .param("tenantId", tenantId.value())
                .param("pairId", pair.sourceId().value())
                .update();
        jdbcClient.sql("""
                        UPDATE connector_items SET status = 'PENDING', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :itemId
                        """)
                .param("tenantId", tenantId.value())
                .param("itemId", itemVersion.itemId().value())
                .update();
        jdbcClient.sql("""
                        INSERT INTO index_attempts (
                            id, tenant_id, connector_id, connector_credential_pair_id,
                            connector_item_id, connector_item_version_id,
                            pair_sequence, item_sequence, status, origin_trace_id, origin_span_id
                        ) VALUES (
                            :id, :tenantId, :connectorId, :pairId,
                            :itemId, :versionId, :pairSequence, :itemSequence, 'NOT_STARTED', :originTraceId, :originSpanId
                        )
                        """)
                .param("id", attemptId.value())
                .param("tenantId", tenantId.value())
                .param("connectorId", pair.connectorId())
                .param("pairId", pair.sourceId().value())
                .param("itemId", itemVersion.itemId().value())
                .param("versionId", itemVersion.versionId())
                .param("pairSequence", pairSequence)
                .param("itemSequence", itemSequence)
                .param("originTraceId", trace == null ? null : trace.traceId())
                .param("originSpanId", trace == null ? null : trace.spanId())
                .update();
        return findById(tenantId, attemptId).orElseThrow();
    }

    public Optional<SourceOperationView> findById(
            TenantId tenantId,
            SourceOperationId operationId
    ) {
        return jdbcClient.sql("""
                        SELECT id, status, created_at, completed_at, error_code
                        FROM index_attempts
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.value())
                .param("id", operationId.value())
                .query((resultSet, ignored) -> operation(resultSet))
                .optional();
    }

    public List<SourceOperationView> list(TenantId tenantId, SourceId sourceId, int limit) {
        return jdbcClient.sql("""
                        SELECT id, status, created_at, completed_at, error_code
                        FROM index_attempts
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                        ORDER BY pair_sequence DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .param("limit", limit)
                .query((resultSet, ignored) -> operation(resultSet))
                .list();
    }

    public void cancelForItem(
            TenantId tenantId,
            SourceId sourceId,
            SourceItemId itemId
    ) {
        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'CANCELLED', claim_token = NULL, lease_expires_at = NULL,
                            completed_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                          AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .param("itemId", itemId.value())
                .update();
    }

    public void cancelForSource(TenantId tenantId, SourceId sourceId) {
        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'CANCELLED', claim_token = NULL, lease_expires_at = NULL,
                            completed_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .update();
    }

    @Override
    @Transactional
    public Optional<IndexWork> claim(
            TenantId tenantId,
            SourceOperationId operationId,
            UUID deliveryId
    ) {
        return WorkLeases.claim(
                jdbcClient,
                "index_attempts",
                tenantId.value(),
                operationId.value(),
                deliveryId,
                this::load
        );
    }

    @Override
    @Transactional
    public boolean renew(IndexWork work) {
        return WorkLeases.renew(
                jdbcClient,
                "index_attempts",
                work.tenantId().value(),
                work.operationId().value(),
                work.claimToken()
        );
    }

    @Override
    @Transactional
    public boolean retry(IndexWork work, String errorCode, int maxAttempts, Duration backoff) {
        WorkLeases.RetryOutcome outcome = WorkLeases.retry(
                jdbcClient,
                "index_attempts",
                work.tenantId().value(),
                work.operationId().value(),
                work.claimToken(),
                errorCode,
                maxAttempts,
                backoff
        );
        if (outcome == WorkLeases.RetryOutcome.EXHAUSTED) {
            markAggregateFailed(work);
        }
        return outcome != WorkLeases.RetryOutcome.STALE;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentId> findMappedDocument(IndexWork work) {
        return sourceDocuments.findMappedDocument(work);
    }

    @Override
    @Transactional
    public boolean complete(IndexWork work, DocumentId documentId) {
        if (!isCurrent(work)) {
            return false;
        }
        int updated = jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'SUCCEEDED', completed_at = CURRENT_TIMESTAMP,
                            claim_token = NULL, lease_expires_at = NULL, error_code = NULL
                        WHERE tenant_id = :tenantId
                          AND id = :attemptId
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :claimToken
                        """)
                .param("tenantId", work.tenantId().value())
                .param("attemptId", work.operationId().value())
                .param("claimToken", work.claimToken())
                .update();
        if (updated != 1) {
            return false;
        }
        sourceDocuments.publishMapping(work, documentId);
        jdbcClient.sql("""
                        UPDATE connector_items
                        SET status = 'INDEXED', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :itemId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("itemId", work.itemId().value())
                .update();
        sources.recomputeStatus(work.tenantId(), work.sourceId(), true);
        return true;
    }

    @Override
    @Transactional
    public boolean fail(IndexWork work, String errorCode) {
        String safeCode = WorkLeases.safeErrorCode(errorCode);
        int updated = jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP,
                            claim_token = NULL, lease_expires_at = NULL, error_code = :errorCode
                        WHERE tenant_id = :tenantId
                          AND id = :attemptId
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :claimToken
                        """)
                .param("errorCode", safeCode)
                .param("tenantId", work.tenantId().value())
                .param("attemptId", work.operationId().value())
                .param("claimToken", work.claimToken())
                .update();
        if (updated == 1) {
            markAggregateFailed(work);
        }
        return updated == 1;
    }

    private void markAggregateFailed(IndexWork work) {
        jdbcClient.sql("""
                        UPDATE connector_items
                        SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND id = :itemId
                          AND status <> 'DELETING'
                        """)
                .param("tenantId", work.tenantId().value())
                .param("itemId", work.itemId().value())
                .update();
        sources.recomputeStatus(work.tenantId(), work.sourceId(), false);
    }

    private IndexWork load(UUID attemptId, UUID token) {
        return jdbcClient.sql("""
                        SELECT attempt.id,
                               attempt.created_at, attempt.started_at, attempt.processing_attempts,
                               attempt.tenant_id,
                               attempt.connector_id,
                               attempt.connector_credential_pair_id,
                               attempt.connector_item_id,
                               object.id AS stored_object_id,
                               object.object_key,
                               object.filename,
                               object.size_bytes,
                               object.declared_media_type,
                               object.content_sha256
                        FROM index_attempts attempt
                        JOIN connector_item_versions version
                          ON version.tenant_id = attempt.tenant_id
                         AND version.id = attempt.connector_item_version_id
                        JOIN stored_objects object
                          ON object.tenant_id = version.tenant_id
                         AND object.id = version.stored_object_id
                         AND object.state = 'ACTIVE'
                        WHERE attempt.id = :id AND attempt.claim_token = :token
                        """)
                .param("id", attemptId)
                .param("token", token)
                .query((resultSet, ignored) -> new IndexWork(
                        new SourceOperationId(resultSet.getObject("id", UUID.class)),
                        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                        resultSet.getObject("connector_id", UUID.class),
                        new SourceId(resultSet.getObject("connector_credential_pair_id", UUID.class)),
                        new SourceItemId(resultSet.getObject("connector_item_id", UUID.class)),
                        token,
                        new StoredObjectReference(
                                new StoredObjectId(resultSet.getObject("stored_object_id", UUID.class)),
                                new ObjectKey(resultSet.getString("object_key")),
                                resultSet.getString("filename"),
                                new ObjectMetadata(
                                        resultSet.getLong("size_bytes"),
                                        resultSet.getString("declared_media_type"),
                                        new ContentSha256(resultSet.getString("content_sha256"))
                                )
                        ),
                        WorkLeases.initialQueueWait(resultSet)
                ))
                .single();
    }

    private boolean isCurrent(IndexWork work) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM index_attempts attempt
                        JOIN tenants tenant ON tenant.id = attempt.tenant_id
                        JOIN connector_credential_pairs pair
                          ON pair.tenant_id = attempt.tenant_id
                         AND pair.id = attempt.connector_credential_pair_id
                        JOIN connector_items item
                          ON item.tenant_id = attempt.tenant_id
                         AND item.id = attempt.connector_item_id
                        WHERE attempt.tenant_id = :tenantId
                          AND attempt.id = :attemptId
                          AND attempt.status = 'IN_PROGRESS'
                          AND attempt.claim_token = :claimToken
                          AND tenant.status = 'ACTIVE'
                          AND pair.status <> 'DELETING'
                          AND NOT EXISTS (
                              SELECT 1 FROM index_attempts newer
                              WHERE newer.tenant_id = attempt.tenant_id
                                AND newer.connector_credential_pair_id = attempt.connector_credential_pair_id
                                AND newer.connector_item_id = attempt.connector_item_id
                                AND newer.pair_sequence > attempt.pair_sequence
                          )
                          AND item.status <> 'DELETING'
                          AND item.current_version_id = attempt.connector_item_version_id
                        """)
                .param("tenantId", work.tenantId().value())
                .param("attemptId", work.operationId().value())
                .param("claimToken", work.claimToken())
                .query(Integer.class)
                .single() == 1;
    }

    @Override
    @Transactional
    public void supersede(IndexWork work) {
        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET status = 'SUPERSEDED', completed_at = CURRENT_TIMESTAMP,
                            claim_token = NULL, lease_expires_at = NULL
                        WHERE tenant_id = :tenantId
                          AND id = :attemptId
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :claimToken
                        """)
                .param("tenantId", work.tenantId().value())
                .param("attemptId", work.operationId().value())
                .param("claimToken", work.claimToken())
                .update();
    }

    private static SourceOperationView operation(ResultSet resultSet) throws SQLException {
        return new SourceOperationView(
                new SourceOperationId(resultSet.getObject("id", UUID.class)),
                SourceOperationType.INDEX,
                JdbcSourceRepository.operationStatus(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant(),
                JdbcSourceRepository.instant(resultSet, "completed_at"),
                resultSet.getString("error_code")
        );
    }
}
