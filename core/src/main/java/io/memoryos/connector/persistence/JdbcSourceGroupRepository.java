package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceAccessChanged;
import io.memoryos.connector.SourceId;
import io.memoryos.iam.group.GroupId;
import io.memoryos.iam.group.GroupIdentity;
import io.memoryos.iam.tenant.TenantId;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceGroupRepository {

    private final JdbcClient jdbcClient;
    private final ApplicationEventPublisher events;

    public JdbcSourceGroupRepository(JdbcClient jdbcClient, ApplicationEventPublisher events) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    public List<GroupIdentity> list(TenantId tenantId, SourceId sourceId) {
        return jdbcClient.sql("""
                        SELECT iam_group.id, iam_group.name
                        FROM source_group_grants source_grant
                        JOIN iam_groups iam_group
                          ON iam_group.tenant_id = source_grant.tenant_id
                         AND iam_group.id = source_grant.group_id
                        WHERE source_grant.tenant_id = :tenantId
                          AND source_grant.connector_credential_pair_id = :sourceId
                          AND iam_group.system_key IS NULL
                        ORDER BY LOWER(iam_group.name), iam_group.id
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .query((resultSet, ignored) -> new GroupIdentity(
                        new GroupId(resultSet.getObject("id", UUID.class)),
                        resultSet.getString("name"),
                        null
                ))
                .list();
    }

    public void replace(TenantId tenantId, SourceId sourceId, Collection<GroupId> groupIds) {
        Objects.requireNonNull(groupIds, "groupIds must not be null");
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
        // Listeners run in this transaction; a rollback also discards their index work.
        events.publishEvent(new SourceAccessChanged(tenantId, sourceId));
        if (values.isEmpty()) {
            return;
        }
        int inserted = jdbcClient.sql("""
                        INSERT INTO source_group_grants (
                            tenant_id, connector_credential_pair_id, group_id
                        )
                        SELECT :tenantId, :sourceId, iam_group.id
                        FROM iam_groups iam_group
                        WHERE iam_group.tenant_id = :tenantId
                          AND iam_group.system_key IS NULL
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
