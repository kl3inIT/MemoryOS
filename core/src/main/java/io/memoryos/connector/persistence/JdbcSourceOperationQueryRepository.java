package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceOperationQueryRepository {

    private final JdbcClient jdbcClient;

    public JdbcSourceOperationQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public Optional<SourceOperationView> findAuthorized(
            TenantId tenantId,
            ActorId actorId,
            SourceOperationId operationId,
            boolean globalRead
    ) {
        return jdbcClient.sql("""
                        SELECT operation_row.id,
                               operation_row.operation,
                               operation_row.status,
                               operation_row.created_at,
                               operation_row.completed_at,
                               operation_row.error_code
                        FROM (
                            SELECT attempt.id,
                                   attempt.tenant_id,
                                   attempt.connector_credential_pair_id AS source_id,
                                   'INDEX' AS operation,
                                   attempt.status,
                                   attempt.created_at,
                                   attempt.completed_at,
                                   attempt.error_code, NULL::uuid AS scope_owner_actor_id
                            FROM index_attempts attempt
                            WHERE attempt.tenant_id = :tenantId AND attempt.id = :operationId
                            UNION ALL
                            SELECT cleanup.id,
                                   cleanup.tenant_id,
                                   cleanup.target_pair_id AS source_id,
                                   cleanup.operation,
                                   cleanup.status,
                                   cleanup.created_at,
                                   cleanup.completed_at,
                                   cleanup.error_code, cleanup.scope_owner_actor_id
                            FROM connector_cleanup_attempts cleanup
                            WHERE cleanup.tenant_id = :tenantId AND cleanup.id = :operationId
                            UNION ALL
                            SELECT sync.id, sync.tenant_id, sync.source_id,
                                   'SYNC_SOURCE' AS operation, sync.status, sync.created_at,
                                   sync.completed_at, sync.error_code, NULL::uuid AS scope_owner_actor_id
                            FROM source_sync_attempts sync
                            WHERE sync.tenant_id = :tenantId AND sync.id = :operationId
                            UNION ALL
                            SELECT selection.id, selection.tenant_id, selection.source_id,
                                   'VALIDATE_GOOGLE_DRIVE_SELECTION' AS operation, selection.status,
                                   selection.created_at, selection.completed_at, selection.error_code,
                                   selection.actor_id AS scope_owner_actor_id
                            FROM google_drive_selection_operations selection
                            WHERE selection.tenant_id = :tenantId AND selection.id = :operationId
                            UNION ALL
                            SELECT sharepoint.id, sharepoint.tenant_id, sharepoint.source_id,
                                   'VALIDATE_SHAREPOINT_SELECTION' AS operation, sharepoint.status,
                                   sharepoint.created_at, sharepoint.completed_at, sharepoint.error_code,
                                   sharepoint.actor_id AS scope_owner_actor_id
                            FROM sharepoint_selection_operations sharepoint
                            WHERE sharepoint.tenant_id = :tenantId AND sharepoint.id = :operationId
                        ) operation_row
                        WHERE EXISTS (
                            SELECT 1
                            FROM tenant_memberships requesting_membership
                            JOIN tenants requesting_tenant
                              ON requesting_tenant.id = requesting_membership.tenant_id
                             AND requesting_tenant.status = 'ACTIVE'
                            JOIN actors requesting_actor
                              ON requesting_actor.id = requesting_membership.actor_id
                             AND requesting_actor.account_type = 'STANDARD'
                            WHERE requesting_membership.tenant_id = operation_row.tenant_id
                              AND requesting_membership.actor_id = :actorId
                              AND requesting_membership.status = 'ACTIVE'
                        )
                          AND (:globalRead OR (operation_row.scope_owner_actor_id = :actorId
                            AND (operation_row.operation = 'DELETE_SOURCE' OR NOT EXISTS (
                                SELECT 1 FROM connector_credential_pairs existing_source
                                WHERE existing_source.tenant_id = operation_row.tenant_id
                                  AND existing_source.id = operation_row.source_id))) OR EXISTS (
                            SELECT 1 FROM connector_credential_pairs pair
                            WHERE pair.tenant_id = operation_row.tenant_id AND pair.id = operation_row.source_id
                              AND %s))
                        ORDER BY CASE WHEN operation_row.operation = 'INDEX' THEN 0 ELSE 1 END
                        LIMIT 1
                        """.formatted(SourceScopeSql.READ))
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .param("operationId", operationId.value())
                .param("globalRead", globalRead)
                .query(JdbcSourceOperationQueryRepository::operation)
                .optional();
    }

    private static SourceOperationView operation(ResultSet resultSet, int ignored) throws SQLException {
        return new SourceOperationView(
                new SourceOperationId(resultSet.getObject("id", UUID.class)),
                SourceOperationType.valueOf(resultSet.getString("operation")),
                JdbcSourceRepository.operationStatus(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant(),
                JdbcSourceRepository.instant(resultSet, "completed_at"),
                resultSet.getString("error_code")
        );
    }
}
