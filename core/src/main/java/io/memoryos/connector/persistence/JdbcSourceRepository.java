package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationStatus;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.connector.SourceStatus;
import io.memoryos.organization.OrganizationId;

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

    public SourcePair createFileSource(OrganizationId organizationId, String name) {
        lockOrganization(organizationId);
        UUID credentialId = ensureNoAuthCredential(organizationId);
        UUID connectorId = UUID.randomUUID();
        SourceId sourceId = new SourceId(UUID.randomUUID());
        jdbcClient.sql("""
                        INSERT INTO connectors (id, organization_id, name, connector_type, status)
                        VALUES (:id, :organizationId, :name, 'FILE', 'ACTIVE')
                        """)
                .param("id", connectorId)
                .param("organizationId", organizationId.value())
                .param("name", name)
                .update();
        jdbcClient.sql("""
                        INSERT INTO connector_credential_pairs (
                            id, organization_id, connector_id, credential_id, access_type, status
                        ) VALUES (
                            :id, :organizationId, :connectorId, :credentialId, 'PUBLIC', 'NOT_STARTED'
                        )
                        """)
                .param("id", sourceId.value())
                .param("organizationId", organizationId.value())
                .param("connectorId", connectorId)
                .param("credentialId", credentialId)
                .update();
        return new SourcePair(connectorId, sourceId, SourceStatus.NOT_STARTED, 0);
    }

    public SourcePair lock(OrganizationId organizationId, SourceId sourceId) {
        return jdbcClient.sql("""
                        SELECT pair.connector_id, pair.status, pair.pair_sequence
                        FROM connector_credential_pairs pair
                        JOIN connectors connector
                          ON connector.organization_id = pair.organization_id
                         AND connector.id = pair.connector_id
                        WHERE pair.organization_id = :organizationId AND pair.id = :pairId
                        FOR UPDATE
                        """)
                .param("organizationId", organizationId.value())
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

    public void markDeleting(OrganizationId organizationId, SourcePair pair) {
        jdbcClient.sql("""
                        UPDATE connectors SET status = 'DELETING', updated_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId AND id = :connectorId
                        """)
                .param("organizationId", organizationId.value())
                .param("connectorId", pair.connectorId())
                .update();
        jdbcClient.sql("""
                        UPDATE connector_credential_pairs SET status = 'DELETING', updated_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId AND id = :pairId
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", pair.sourceId().value())
                .update();
    }

    public Optional<SourceOperationView> findCleanup(
            OrganizationId organizationId,
            SourceOperationType type,
            String targetKey
    ) {
        return jdbcClient.sql("""
                        SELECT id, operation, status, created_at, completed_at, error_code
                        FROM connector_cleanup_attempts
                        WHERE organization_id = :organizationId
                          AND operation = :operation AND target_key = :targetKey
                        """)
                .param("organizationId", organizationId.value())
                .param("operation", type.name())
                .param("targetKey", targetKey)
                .query(JdbcSourceRepository::cleanupOperation)
                .optional();
    }

    public SourceOperationView createCleanup(
            SourceOperationId operationId,
            OrganizationId organizationId,
            SourceOperationType type,
            String targetKey,
            SourceId sourceId,
            @Nullable SourceItemId itemId
    ) {
        jdbcClient.sql("""
                        INSERT INTO connector_cleanup_attempts (
                            id, organization_id, operation, target_key,
                            target_pair_id, target_item_id, status
                        ) VALUES (
                            :id, :organizationId, :operation, :targetKey,
                            :pairId, :itemId, 'NOT_STARTED'
                        )
                        """)
                .param("id", operationId.value())
                .param("organizationId", organizationId.value())
                .param("operation", type.name())
                .param("targetKey", targetKey)
                .param("pairId", sourceId.value())
                .param("itemId", itemId == null ? null : itemId.value())
                .update();
        return findCleanupById(organizationId, operationId).orElseThrow();
    }

    public Optional<SourceOperationView> findCleanupById(
            OrganizationId organizationId,
            SourceOperationId operationId
    ) {
        return jdbcClient.sql("""
                        SELECT id, operation, status, created_at, completed_at, error_code
                        FROM connector_cleanup_attempts
                        WHERE organization_id = :organizationId AND id = :id
                        """)
                .param("organizationId", organizationId.value())
                .param("id", operationId.value())
                .query(JdbcSourceRepository::cleanupOperation)
                .optional();
    }

    public void supersedeItemCleanups(OrganizationId organizationId, SourceId sourceId) {
        jdbcClient.sql("""
                        UPDATE connector_cleanup_attempts
                        SET status = 'SUPERSEDED', claim_token = NULL, lease_expires_at = NULL,
                            completed_at = CURRENT_TIMESTAMP
                        WHERE organization_id = :organizationId
                          AND target_pair_id = :pairId
                          AND operation = 'REMOVE_ITEM'
                          AND status IN ('NOT_STARTED', 'IN_PROGRESS')
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .update();
    }

    private void lockOrganization(OrganizationId organizationId) {
        boolean active = jdbcClient.sql("""
                        SELECT id FROM organizations
                        WHERE id = :organizationId AND status = 'ACTIVE'
                        FOR UPDATE
                        """)
                .param("organizationId", organizationId.value())
                .query(UUID.class)
                .optional()
                .isPresent();
        if (!active) {
            throw SourceException.notOwner();
        }
    }

    private UUID ensureNoAuthCredential(OrganizationId organizationId) {
        Optional<UUID> existing = jdbcClient.sql("""
                        SELECT id FROM credentials
                        WHERE organization_id = :organizationId AND credential_kind = 'NO_AUTH'
                        """)
                .param("organizationId", organizationId.value())
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID credentialId = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO credentials (id, organization_id, credential_kind, status)
                        VALUES (:id, :organizationId, 'NO_AUTH', 'ACTIVE')
                        """)
                .param("id", credentialId)
                .param("organizationId", organizationId.value())
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
