package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupIdentity;
import io.memoryos.iam.GroupSystemKey;
import io.memoryos.iam.TenantId;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceGroupRepository {

    private final JdbcClient jdbcClient;

    public JdbcSourceGroupRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public GroupId adminGroupId(TenantId tenantId) {
        return jdbcClient.sql("""
                        SELECT id
                        FROM iam_groups
                        WHERE tenant_id = :tenantId AND system_key = 'ADMIN'
                        """)
                .param("tenantId", tenantId.value())
                .query((resultSet, ignored) ->
                        new GroupId(resultSet.getObject("id", UUID.class)))
                .single();
    }

    public List<GroupIdentity> list(TenantId tenantId, SourceId sourceId) {
        return jdbcClient.sql("""
                        SELECT iam_group.id, iam_group.name, iam_group.system_key
                        FROM source_group_grants source_grant
                        JOIN iam_groups iam_group
                          ON iam_group.tenant_id = source_grant.tenant_id
                         AND iam_group.id = source_grant.group_id
                        WHERE source_grant.tenant_id = :tenantId
                          AND source_grant.connector_credential_pair_id = :sourceId
                        ORDER BY LOWER(iam_group.name), iam_group.id
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .query((resultSet, ignored) -> {
                    String systemKey = resultSet.getString("system_key");
                    return new GroupIdentity(
                            new GroupId(resultSet.getObject("id", UUID.class)),
                            resultSet.getString("name"),
                            systemKey == null ? null : GroupSystemKey.valueOf(systemKey)
                    );
                })
                .list();
    }

    public void replace(TenantId tenantId, SourceId sourceId, Collection<GroupId> groupIds) {
        Objects.requireNonNull(groupIds, "groupIds must not be null");
        if (groupIds.isEmpty()) {
            throw new IllegalArgumentException("source groupIds must not be empty");
        }
        List<UUID> values = groupIds.stream()
                .map(groupId -> Objects.requireNonNull(groupId, "groupId must not be null").value())
                .toList();
        jdbcClient.sql("""
                        DELETE FROM source_group_grants
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :sourceId
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .update();
        int inserted = jdbcClient.sql("""
                        INSERT INTO source_group_grants (
                            tenant_id, connector_credential_pair_id, group_id
                        )
                        SELECT :tenantId, :sourceId, iam_group.id
                        FROM iam_groups iam_group
                        WHERE iam_group.tenant_id = :tenantId
                          AND iam_group.id IN (:groupIds)
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .param("groupIds", values)
                .update();
        if (inserted != values.size()) {
            throw new IllegalStateException("Validated source groups changed while the Tenant lock was held");
        }
    }
}
