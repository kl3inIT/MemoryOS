package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationType;
import io.memoryos.connector.SourceOperationView;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;

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
                                   attempt.error_code
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
                                   cleanup.error_code
                            FROM connector_cleanup_attempts cleanup
                            WHERE cleanup.tenant_id = :tenantId AND cleanup.id = :operationId
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
                          AND (:globalRead OR EXISTS (
                            SELECT 1
                            FROM source_group_grants scoped_grant
                            JOIN iam_groups scoped_group
                              ON scoped_group.tenant_id = scoped_grant.tenant_id
                             AND scoped_group.id = scoped_grant.group_id
                             AND scoped_group.system_key IS NULL
                            JOIN iam_group_memberships scoped_membership
                              ON scoped_membership.tenant_id = scoped_grant.tenant_id
                             AND scoped_membership.group_id = scoped_grant.group_id
                             AND scoped_membership.actor_id = :actorId
                             AND scoped_membership.is_manager = TRUE
                            WHERE scoped_grant.tenant_id = operation_row.tenant_id
                              AND scoped_grant.connector_credential_pair_id = operation_row.source_id
                        )
                          )
                        ORDER BY CASE WHEN operation_row.operation = 'INDEX' THEN 0 ELSE 1 END
                        LIMIT 1
                        """)
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
