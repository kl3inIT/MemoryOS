package io.memoryos.connector.persistence;

import io.memoryos.connector.CleanupWork;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.tenant.TenantId;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcCleanupAttemptRepository {
    private final JdbcClient jdbcClient;

    public JdbcCleanupAttemptRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public Optional<CleanupWork> claim(TenantId tenantId, SourceOperationId operationId, UUID deliveryId) {
        return WorkLeases.claim(
                jdbcClient,
                "connector_cleanup_attempts",
                tenantId.value(),
                operationId.value(),
                deliveryId,
                this::load
        );
    }

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

    public boolean ownsClaim(CleanupWork work) {
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

    public void removeItemRows(CleanupWork work) {
        SourceItemId itemId = Objects.requireNonNull(work.itemId(), "REMOVE_ITEM requires itemId");
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
    }

    public UUID findConnectorId(CleanupWork work) {
        return jdbcClient.sql("""
                        SELECT connector_id FROM connector_credential_pairs
                        WHERE tenant_id = :tenantId AND id = :pairId
                        """)
                .param("tenantId", work.tenantId().value())
                .param("pairId", work.sourceId().value())
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    public void deleteSourceRows(CleanupWork work, UUID connectorId) {
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
    }

    public boolean complete(CleanupWork work, String status, String errorCode) {
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

    private CleanupWork load(UUID operationId, UUID token) {
        return jdbcClient.sql("""
                        SELECT id, tenant_id, operation, target_pair_id, target_item_id
                        FROM connector_cleanup_attempts
                        WHERE id = :id AND claim_token = :token
                        """)
                .param("id", operationId)
                .param("token", token)
                .query((resultSet, ignored) -> new CleanupWork(
                        new SourceOperationId(resultSet.getObject("id", UUID.class)),
                        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                        io.memoryos.connector.SourceOperationType.valueOf(resultSet.getString("operation")),
                        new io.memoryos.connector.SourceId(resultSet.getObject("target_pair_id", UUID.class)),
                        optionalItemId(resultSet.getObject("target_item_id", UUID.class)),
                        token
                ))
                .single();
    }

    private static SourceItemId optionalItemId(UUID value) {
        return value == null ? null : new SourceItemId(value);
    }
}
