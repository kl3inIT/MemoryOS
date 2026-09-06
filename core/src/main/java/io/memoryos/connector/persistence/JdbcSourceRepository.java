package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.connector.SourceOperationStatus;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.SourceStatus;
import io.memoryos.tenant.TenantId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceRepository {

    private final JdbcClient jdbcClient;

    public JdbcSourceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public SourcePair createFileSource(TenantId tenantId, String name) {
        lockTenant(tenantId);
        UUID credentialId = ensureNoAuthCredential(tenantId);
        UUID connectorId = UUID.randomUUID();
        SourceId sourceId = new SourceId(UUID.randomUUID());
        jdbcClient.sql("""
                        INSERT INTO connectors (id, tenant_id, name, connector_type, status)
                        VALUES (:id, :tenantId, :name, 'FILE', 'ACTIVE')
                        """)
                .param("id", connectorId)
                .param("tenantId", tenantId.value())
                .param("name", name)
                .update();
        jdbcClient.sql("""
                        INSERT INTO connector_credential_pairs (
                            id, tenant_id, connector_id, credential_id, access_type, status
                        ) VALUES (
                            :id, :tenantId, :connectorId, :credentialId, 'PUBLIC', 'NOT_STARTED'
                        )
                        """)
                .param("id", sourceId.value())
                .param("tenantId", tenantId.value())
                .param("connectorId", connectorId)
                .param("credentialId", credentialId)
                .update();
        return new SourcePair(connectorId, sourceId, SourceStatus.NOT_STARTED, 0);
    }

    public SourcePair lock(TenantId tenantId, SourceId sourceId) {
        return jdbcClient.sql("""
                        SELECT pair.connector_id, pair.status, pair.pair_sequence
                        FROM connector_credential_pairs pair
                        JOIN connectors connector
                          ON connector.tenant_id = pair.tenant_id
                         AND connector.id = pair.connector_id
                        WHERE pair.tenant_id = :tenantId AND pair.id = :pairId
                        FOR UPDATE
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .query((resultSet, ignored) -> new SourcePair(
                        resultSet.getObject("connector_id", UUID.class),
                        sourceId,
                        SourceStatus.valueOf(resultSet.getString("status")),
                        resultSet.getLong("pair_sequence")
                ))
                .optional()
                .orElseThrow(SourceException::notFound);
    }

    public void markDeleting(TenantId tenantId, SourcePair pair) {
        jdbcClient.sql("""
                        UPDATE connectors SET status = 'DELETING', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :connectorId
                        """)
                .param("tenantId", tenantId.value())
                .param("connectorId", pair.connectorId())
                .update();
        jdbcClient.sql("""
                        UPDATE connector_credential_pairs SET status = 'DELETING', updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :pairId
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", pair.sourceId().value())
                .update();
    }

    public Optional<SourceOperationView> findCleanup(
            TenantId tenantId,
            SourceOperationType type,
            String targetKey
    ) {
        return jdbcClient.sql("""
                        SELECT id, operation, status, created_at, completed_at, error_code
                        FROM connector_cleanup_attempts
                        WHERE tenant_id = :tenantId
                          AND operation = :operation AND target_key = :targetKey
                        """)
                .param("tenantId", tenantId.value())
                .param("operation", type.name())
                .param("targetKey", targetKey)
                .query(JdbcSourceRepository::cleanupOperation)
                .optional();
    }

    public SourceOperationView createCleanup(
            SourceOperationId operationId,
            TenantId tenantId,
            SourceOperationType type,
            String targetKey,
            SourceId sourceId,
            @Nullable SourceItemId itemId
    ) {
        var trace = SourceOperationTraceContext.current();
        jdbcClient.sql("""
                        INSERT INTO connector_cleanup_attempts (
                            id, tenant_id, operation, target_key,
                            target_pair_id, target_item_id, status, origin_trace_id, origin_span_id
                        ) VALUES (
                            :id, :tenantId, :operation, :targetKey,
                            :pairId, :itemId, 'NOT_STARTED', :originTraceId, :originSpanId
                        )
                        """)
                .param("id", operationId.value())
                .param("tenantId", tenantId.value())
                .param("operation", type.name())
                .param("targetKey", targetKey)
                .param("pairId", sourceId.value())
                .param("itemId", itemId == null ? null : itemId.value())
                .param("originTraceId", trace == null ? null : trace.traceId())
                .param("originSpanId", trace == null ? null : trace.spanId())
                .update();
        return findCleanupById(tenantId, operationId).orElseThrow();
    }

    public Optional<SourceOperationView> findCleanupById(
            TenantId tenantId,
            SourceOperationId operationId
    ) {
        return jdbcClient.sql("""
                        SELECT id, operation, status, created_at, completed_at, error_code
                        FROM connector_cleanup_attempts
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId.value())
                .param("id", operationId.value())
                .query(JdbcSourceRepository::cleanupOperation)
                .optional();
    }

    public void supersedeItemCleanups(TenantId tenantId, SourceId sourceId) {
        jdbcClient.sql("""
                        UPDATE connector_cleanup_attempts
                        SET status = 'SUPERSEDED', claim_token = NULL, lease_expires_at = NULL,
                            completed_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId
                          AND target_pair_id = :pairId
                          AND operation = 'REMOVE_ITEM'
                          AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .update();
    }

    /**
     * Re-derives the pair's status, document count, and latest error from its attempts and mappings.
     * A pair that is DELETING keeps that status; {@code indexSucceeded} also stamps {@code last_succeeded_at}.
     */
    public void recomputeStatus(TenantId tenantId, SourceId sourceId, boolean indexSucceeded) {
        jdbcClient.sql("""
                        UPDATE connector_credential_pairs
                        SET document_count = (
                                SELECT COUNT(*) FROM documents_by_connector_credential_pair mapping
                                WHERE mapping.tenant_id = :tenantId
                                  AND mapping.connector_credential_pair_id = :pairId
                                  AND mapping.retrieval_eligible = TRUE
                            ),
                            error_code = (
                                SELECT attempt.error_code FROM index_attempts attempt
                                WHERE attempt.tenant_id = :tenantId
                                  AND attempt.connector_credential_pair_id = :pairId
                                  AND attempt.status = 'FAILED'
                                ORDER BY attempt.pair_sequence DESC
                                LIMIT 1
                            ),
                            status = CASE
                                WHEN status = 'DELETING' THEN 'DELETING'
                                WHEN EXISTS (
                                    SELECT 1 FROM index_attempts attempt
                                    WHERE attempt.tenant_id = :tenantId
                                      AND attempt.connector_credential_pair_id = :pairId
                                      AND attempt.status IN ('NOT_STARTED', 'IN_PROGRESS')
                                ) THEN 'INDEXING'
                                WHEN EXISTS (
                                    SELECT 1 FROM documents_by_connector_credential_pair mapping
                                    WHERE mapping.tenant_id = :tenantId
                                      AND mapping.connector_credential_pair_id = :pairId
                                      AND mapping.retrieval_eligible = TRUE
                                ) THEN 'ACTIVE'
                                WHEN EXISTS (
                                    SELECT 1 FROM index_attempts attempt
                                    WHERE attempt.tenant_id = :tenantId
                                      AND attempt.connector_credential_pair_id = :pairId
                                      AND attempt.status = 'FAILED'
                                ) THEN 'FAILED'
                                ELSE 'NOT_STARTED'
                            END,
                            last_succeeded_at = CASE
                                WHEN :indexSucceeded THEN CURRENT_TIMESTAMP
                                ELSE last_succeeded_at
                            END,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE tenant_id = :tenantId AND id = :pairId
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .param("indexSucceeded", indexSucceeded)
                .update();
    }

    private void lockTenant(TenantId tenantId) {
        boolean active = jdbcClient.sql("""
                        SELECT id FROM tenants
                        WHERE id = :tenantId AND status = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("tenantId", tenantId.value())
                .query(UUID.class)
                .optional()
                .isPresent();
        if (!active) {
            throw SourceException.notOwner();
        }
    }

    private UUID ensureNoAuthCredential(TenantId tenantId) {
        Optional<UUID> existing = jdbcClient.sql("""
                        SELECT id FROM credentials
                        WHERE tenant_id = :tenantId AND credential_kind = 'NO_AUTH'
                        """)
                .param("tenantId", tenantId.value())
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID credentialId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO credentials (id, tenant_id, credential_kind, status)
                        VALUES (:id, :tenantId, 'NO_AUTH', 'ACTIVE')
                        """)
                .param("id", credentialId)
                .param("tenantId", tenantId.value())
                .update();
        return credentialId;
    }

    private static SourceOperationView cleanupOperation(ResultSet resultSet, int ignored) throws SQLException {
        return new SourceOperationView(
                new SourceOperationId(resultSet.getObject("id", UUID.class)),
                SourceOperationType.valueOf(resultSet.getString("operation")),
                operationStatus(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant(),
                instant(resultSet, "completed_at"),
                resultSet.getString("error_code")
        );
    }

    static SourceOperationStatus operationStatus(String value) {
        return switch (value) {
            case "NOT_STARTED" -> SourceOperationStatus.NOT_STARTED;
            case "IN_PROGRESS" -> SourceOperationStatus.IN_PROGRESS;
            case "SUCCEEDED" -> SourceOperationStatus.SUCCEEDED;
            case "FAILED" -> SourceOperationStatus.FAILED;
            case "SUPERSEDED", "CANCELLED" -> SourceOperationStatus.SUPERSEDED;
            default -> throw new IllegalStateException("unsupported source operation status: " + value);
        };
    }

    static @Nullable Instant instant(ResultSet resultSet, String column) throws SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record SourcePair(UUID connectorId, SourceId sourceId, SourceStatus status, long pairSequence) {
    }
}
