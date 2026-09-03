package io.memoryos.connector.persistence;

import io.memoryos.connector.CleanupWork;
import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.document.DocumentId;
import io.memoryos.tenant.TenantId;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcCleanupAttemptRepository implements ConnectorCleanupPort {


    private final JdbcClient jdbcClient;
    private final JdbcSourceRepository sources;
    private final JdbcSourceDocumentRepository sourceDocuments;
    private final DocumentCommandPort documents;

    public JdbcCleanupAttemptRepository(
            JdbcClient jdbcClient,
            JdbcSourceRepository sources,
            JdbcSourceDocumentRepository sourceDocuments,
            DocumentCommandPort documents
    ) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.sources = Objects.requireNonNull(sources, "sources must not be null");
        this.sourceDocuments = Objects.requireNonNull(sourceDocuments, "sourceDocuments must not be null");
        this.documents = Objects.requireNonNull(documents, "documents must not be null");
    }

    @Override
    @Transactional
    public Optional<CleanupWork> claim(
            TenantId tenantId,
            SourceOperationId operationId,
            UUID deliveryId
    ) {
        return WorkLeases.claim(
                jdbcClient,
                "connector_cleanup_attempts",
                tenantId.value(),
                operationId.value(),
                deliveryId,
                this::load
        );
    }


    @Override
    @Transactional
    public boolean retry(CleanupWork work, String errorCode, int maxAttempts, Duration backoff) {
        return WorkLeases.retry(
                jdbcClient,
                "connector_cleanup_attempts",
                work.tenantId().value(),
                work.operationId().value(),
                work.claimToken(),
                errorCode,
                maxAttempts,
                backoff
        ) != WorkLeases.RetryOutcome.STALE;
    }

    @Override
    @Transactional
    public boolean execute(CleanupWork work) {
        if (!ownsClaim(work)) {
            return false;
        }
        switch (work.type()) {
            case REMOVE_ITEM -> removeItem(work);
            case DELETE_SOURCE -> deleteSource(work);
            default -> throw new IllegalStateException("unsupported cleanup operation: " + work.type());
        }
        return complete(work, "SUCCEEDED", null);
    }

    @Override
    @Transactional
    public boolean fail(CleanupWork work, String errorCode) {
        return complete(work, "FAILED", WorkLeases.safeErrorCode(errorCode));
    }

    private CleanupWork load(UUID operationId, UUID token) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, operation, target_pair_id, target_item_id
                        FROM connector_cleanup_attempts
                        WHERE id = :id AND claim_token = :token
                        """)
                .param("id", operationId)
                .param("token", token)
                .query((resultSet, ignored) -> {
                    UUID itemId = resultSet.getObject("target_item_id", UUID.class);
                    return new CleanupWork(
                            new SourceOperationId(resultSet.getObject("id", UUID.class)),
                            new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                            SourceOperationType.valueOf(resultSet.getString("operation")),
                            new SourceId(resultSet.getObject("target_pair_id", UUID.class)),
                            itemId == null ? null : new SourceItemId(itemId),
                            token
                    );
                })
                .single();
    }

    private boolean ownsClaim(CleanupWork work) {
        return jdbcClient.sql("""
                        SELECT id FROM connector_cleanup_attempts
                        WHERE tenant_id = :tenantId
                          AND id = :id
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :token
                        FOR UPDATE
                        """)
                .param("tenantId", work.tenantId().value())
                .param("id", work.operationId().value())
                .param("token", work.claimToken())
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    private void removeItem(CleanupWork work) {
        SourceItemId itemId = Objects.requireNonNull(work.itemId(), "REMOVE_ITEM requires itemId");
        List<UUID> documentIds = sourceDocuments.removeItemMappings(work.tenantId(), work.sourceId(), itemId);
        jdbcClient.sql("""
                        DELETE FROM index_attempts
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                          AND connector_item_id = :itemId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("pairId", work.sourceId().value())
                .param("itemId", itemId.value())
                .update();
        jdbcClient.sql("""
                        UPDATE connector_items SET current_version_id = NULL
                        WHERE tenant_id = :tenantId AND id = :itemId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("itemId", itemId.value())
                .update();
        jdbcClient.sql("""
                        DELETE FROM connector_item_versions
                        WHERE tenant_id = :tenantId AND connector_item_id = :itemId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("itemId", itemId.value())
                .update();
        jdbcClient.sql("""
                        DELETE FROM connector_items
                        WHERE tenant_id = :tenantId AND id = :itemId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("itemId", itemId.value())
                .update();
        documents.removeUnreferenced(work.tenantId(), documentIds.stream().map(DocumentId::new).toList());
        sources.recomputeStatus(work.tenantId(), work.sourceId(), false);
    }

    private void deleteSource(CleanupWork work) {
        UUID connectorId = jdbcClient.sql("""
                        SELECT connector_id FROM connector_credential_pairs
                        WHERE tenant_id = :tenantId AND id = :pairId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("pairId", work.sourceId().value())
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (connectorId == null) {
            complete(work, "SUPERSEDED", null);
            return;
        }
        List<UUID> documentIds = sourceDocuments.removeSourceMappings(work.tenantId(), work.sourceId());
        jdbcClient.sql("""
                        DELETE FROM index_attempts
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :pairId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("pairId", work.sourceId().value())
                .update();
        jdbcClient.sql("""
                        UPDATE connector_items SET current_version_id = NULL
                        WHERE tenant_id = :tenantId AND connector_id = :connectorId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("connectorId", connectorId)
                .update();
        jdbcClient.sql("""
                        DELETE FROM connector_item_versions
                        WHERE tenant_id = :tenantId AND connector_id = :connectorId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("connectorId", connectorId)
                .update();
        jdbcClient.sql("""
                        DELETE FROM connector_items
                        WHERE tenant_id = :tenantId AND connector_id = :connectorId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("connectorId", connectorId)
                .update();
        jdbcClient.sql("""
                        DELETE FROM connector_credential_pairs
                        WHERE tenant_id = :tenantId AND id = :pairId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("pairId", work.sourceId().value())
                .update();
        jdbcClient.sql("""
                        DELETE FROM connectors
                        WHERE tenant_id = :tenantId AND id = :connectorId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("connectorId", connectorId)
                .update();
        documents.removeUnreferenced(work.tenantId(), documentIds.stream().map(DocumentId::new).toList());
    }

    private boolean complete(CleanupWork work, String status, String errorCode) {
        int updated = jdbcClient.sql("""
                        UPDATE connector_cleanup_attempts
                        SET status = :status, error_code = :errorCode,
                            completed_at = CURRENT_TIMESTAMP,
                            claim_token = NULL, lease_expires_at = NULL
                        WHERE tenant_id = :tenantId
                          AND id = :id
                          AND status = 'IN_PROGRESS'
                          AND claim_token = :token
                        """)
                .param("status", status)
                .param("errorCode", errorCode)
                .param("tenantId", work.tenantId().value())
                .param("id", work.operationId().value())
                .param("token", work.claimToken())
                .update();
        return updated == 1;
    }
}
